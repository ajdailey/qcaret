package io.github.ajdailey.qcaret.launcher;

import io.github.ajdailey.qcaret.app.cli.CommandLine;
import io.github.ajdailey.qcaret.app.cli.CommandLineException;
import io.github.ajdailey.qcaret.app.cli.CommandLineParser;
import io.github.ajdailey.qcaret.app.cli.StartupRequest;
import io.github.ajdailey.qcaret.app.cli.Version;
import io.github.ajdailey.qcaret.app.config.ConfigStore;
import io.github.ajdailey.qcaret.app.config.Preferences;
import io.github.ajdailey.qcaret.app.config.SettingsLocation;
import io.github.ajdailey.qcaret.app.ipc.InstanceClient;
import io.github.ajdailey.qcaret.app.ipc.InstanceServer;
import io.github.ajdailey.qcaret.core.io.SafePaths;
import io.github.ajdailey.qcaret.core.text.LogText;
import io.github.ajdailey.qcaret.core.util.os.OsTraits;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Parses the command line, configures logging, resolves the settings directory and loads the
 * settings, configures the platform, opens the debug log file with {@code -l}, then hands over to
 * {@link Gui}. All of it runs before the Display exists, so the UI thread never waits on it. Exit
 * statuses: 0 normal, 1 bad command line or settings directory, 3 SWT failed to load, 4 UI-thread
 * failures quit the application ({@link UiFailures}).
 *
 * <p>Without {@code -m} the launch becomes the primary instance of its settings directory, or hands
 * its files to the running primary and exits 0. After a failed handoff it claims the lock a primary
 * that quit or died released, or hands over to the launch that claimed it first. Any other failure,
 * or a settings directory another user owns or can write, logs one WARN and the launch opens its
 * own window, as with {@code -m}. The primary writes {@code qcaret.log}, any other process {@code
 * qcaret-<pid>.log}.
 */
public final class Launcher {
  static final int EXIT_USAGE = 1;
  static final int EXIT_SWT = 3;
  static final int EXIT_UI_FAILURE = 4;

  /** Handoffs one launch tries; each one after the first needs a new primary. */
  private static final int HANDOFF_ATTEMPTS = 3;

  private static final long SUCCESSOR_POLL_MILLIS = 50;

  private static final Logger LOG = LoggerFactory.getLogger(Launcher.class);

  private final PrintStream out;
  private final PrintStream err;

  /**
   * Creates the launcher.
   *
   * @param out receives help and version text
   * @param err receives command-line errors
   */
  public Launcher(PrintStream out, PrintStream err) {
    this.out = out;
    this.err = err;
  }

  /**
   * Runs QCaret to completion.
   *
   * @param args the command line
   * @return the exit status
   */
  public int run(String[] args) {
    CommandLine line;
    try {
      line = new CommandLineParser(Path.of("").toAbsolutePath()).parse(args);
    } catch (CommandLineException e) {
      err.println("qcaret: " + e.getMessage());
      err.println("Try 'qcaret --help'.");
      return EXIT_USAGE;
    }
    if (line.action() == CommandLine.Action.HELP) {
      out.print(CommandLineParser.usage());
      return 0;
    }
    if (line.action() == CommandLine.Action.VERSION) {
      out.println("qcaret " + Version.get());
      return 0;
    }
    LogConfigurator.setLevel(line.logLevel());
    Thread.setDefaultUncaughtExceptionHandler(new FailureLogger());
    StartupRequest request = line.request();
    Path settings = null;
    if (request.settingsDirectory() != null) {
      try {
        settings = SafePaths.settingsDirectory(request.settingsDirectory());
      } catch (IOException e) {
        err.println("qcaret: --settings: " + LogText.oneLine(e.getMessage()));
        return EXIT_USAGE;
      }
    }
    SettingsLocation location = SettingsLocation.fromSystem(settings);
    PlatformSetup.apply(OsTraits.current(), location);
    if (LOG.isInfoEnabled()) {
      LOG.info(
          "QCaret {} on Java {} from {}",
          LogText.oneLine(Version.get()),
          LogText.oneLine(Runtime.version()),
          LogText.oneLine(System.getProperty("java.home")));
    }
    ConfigStore config = new ConfigStore(location);
    Preferences preferences = config.load();
    InstanceServer server = null;
    boolean beside = false;
    if (!request.multi()) {
      Handoff handoff = handOff(location.directory(), request);
      if (handoff == Handoff.HANDED) {
        return 0;
      }
      server = handoff == Handoff.PRIMARY ? primary : null;
      beside = handoff == Handoff.BESIDE;
    }
    try {
      FileLog logFile =
          new FileLog(
              location.directory(),
              FileLog.fileName(server != null, ProcessHandle.current().pid()));
      if (line.logToFile() || preferences.debugLogFile()) {
        try {
          logFile.attach();
        } catch (IOException e) {
          LOG.warn("Cannot open the debug log file: {}", LogText.oneLine(e.getMessage()));
        }
      }
      return new Gui(request, config, preferences, err, null, server, logFile, beside).run();
    } catch (IOException e) {
      err.println("qcaret: " + LogText.oneLine(e.getMessage()));
      return EXIT_USAGE;
    } finally {
      if (server != null) {
        server.close();
      }
    }
  }

  /** What the single-instance step decided. */
  enum Handoff {
    /** The files went to the running instance. */
    HANDED,
    /** This launch is the primary; {@link #primary} holds the listener. */
    PRIMARY,
    /** No handoff was possible; this launch runs on its own. */
    ALONE,
    /** A primary holds the lock and the handoff failed; this launch runs without the session. */
    BESIDE
  }

  private InstanceServer primary;

  /** Returns the listener after {@link #handOff} answered {@link Handoff#PRIMARY}. */
  InstanceServer primary() {
    return primary;
  }

  /** Becomes the primary instance, or hands the launch to it. */
  Handoff handOff(Path directory, StartupRequest request) {
    try {
      SafePaths.settingsDirectory(directory);
    } catch (IOException e) {
      LOG.warn("Single-instance handoff off: {}", LogText.oneLine(e.getMessage()));
      return Handoff.ALONE;
    }
    String refusal = InstanceClient.refusal(directory, System.getProperty("user.name"));
    if (refusal != null) {
      LOG.warn("Single-instance handoff off: {}", LogText.oneLine(refusal));
      return Handoff.ALONE;
    }
    try {
      primary = InstanceServer.claim(directory);
    } catch (IOException e) {
      LOG.warn("Single-instance handoff off: {}", LogText.oneLine(e.getMessage()));
      return Handoff.ALONE;
    }
    if (primary != null) {
      return Handoff.PRIMARY;
    }
    for (int attempt = 1; ; attempt++) {
      String tried = InstanceClient.endpointId(directory);
      try {
        InstanceClient.handOff(directory, request);
        if (LOG.isInfoEnabled()) {
          LOG.info("Handed {} files to the running instance", request.files().size());
        }
        return Handoff.HANDED;
      } catch (IOException e) {
        boolean next = attempt < HANDOFF_ATTEMPTS && awaitSuccessor(directory, tried);
        if (primary != null) {
          return Handoff.PRIMARY;
        }
        if (!next) {
          LOG.warn(
              "Handoff to the running instance failed; opening a window: {}",
              LogText.oneLine(e.getMessage()));
          return Handoff.BESIDE;
        }
      }
    }
  }

  /**
   * Polls for what follows a failed handoff: this launch claims the lock, leaving {@link #primary}
   * set, or another launch claims it and writes a new endpoint. Launches fail together when a
   * primary ends, some before it releases the lock, so one claim attempt is not enough.
   *
   * @return false when neither happened within {@link InstanceClient#ENDPOINT_WAIT}, so the primary
   *     that failed the handoff still holds the lock
   */
  private boolean awaitSuccessor(Path directory, String tried) {
    long deadline = System.nanoTime() + InstanceClient.ENDPOINT_WAIT.toNanos();
    try {
      while (true) {
        primary = InstanceServer.claim(directory);
        if (primary != null) {
          return true;
        }
        String now = InstanceClient.endpointId(directory);
        if (now != null && !now.equals(tried)) {
          return true;
        }
        if (System.nanoTime() >= deadline) {
          return false;
        }
        Thread.sleep(SUCCESSOR_POLL_MILLIS);
      }
    } catch (IOException e) {
      LOG.debug("No claim after the failed handoff: {}", LogText.oneLine(e.getMessage()));
      return false;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  /** Logs a failure that escaped a task or a thread. */
  static final class FailureLogger implements Thread.UncaughtExceptionHandler {
    @Override
    public void uncaughtException(Thread thread, Throwable error) {
      LOG.error("Unhandled failure on thread {}", LogText.oneLine(thread.getName()), error);
    }
  }
}
