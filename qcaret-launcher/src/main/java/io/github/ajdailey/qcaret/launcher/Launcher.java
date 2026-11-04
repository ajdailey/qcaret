package io.github.ajdailey.qcaret.launcher;

import io.github.ajdailey.qcaret.app.cli.CommandLine;
import io.github.ajdailey.qcaret.app.cli.CommandLineException;
import io.github.ajdailey.qcaret.app.cli.CommandLineParser;
import io.github.ajdailey.qcaret.app.cli.StartupRequest;
import io.github.ajdailey.qcaret.app.cli.Version;
import io.github.ajdailey.qcaret.app.config.ConfigStore;
import io.github.ajdailey.qcaret.app.config.Preferences;
import io.github.ajdailey.qcaret.app.config.SettingsLocation;
import io.github.ajdailey.qcaret.core.io.SafePaths;
import io.github.ajdailey.qcaret.core.text.LogText;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Parses the command line, configures logging, resolves the settings directory and loads the
 * settings, configures the platform, opens the debug log file with {@code -l}, then hands over to
 * {@link Gui}. All of it runs before the Display exists, so the UI thread never waits on it. Exit
 * statuses: 0 normal, 1 bad command line or settings directory, 3 SWT failed to load.
 *
 * <p>Every process writes its debug log file as {@code qcaret-<pid>.log}; the single-instance lock
 * that names one process the owner of {@code qcaret.log} is not built yet.
 */
public final class Launcher {
  static final int EXIT_USAGE = 1;
  static final int EXIT_SWT = 3;

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
   * Runs QCaret to completion. A nonzero status is returned only before the Display exists.
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
    LogbackConfigurator.setLevel(line.logLevel());
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
    PlatformSetup.apply(System.getProperty("os.name"), location);
    if (LOG.isInfoEnabled()) {
      LOG.info(
          "QCaret {} on Java {} from {}",
          LogText.oneLine(Version.get()),
          LogText.oneLine(Runtime.version()),
          LogText.oneLine(System.getProperty("java.home")));
    }
    ConfigStore config = new ConfigStore(location);
    Preferences preferences = config.load();
    try {
      FileLog logFile =
          new FileLog(location.directory(), FileLog.fileName(false, ProcessHandle.current().pid()));
      if (line.logToFile() || preferences.debugLogFile()) {
        try {
          logFile.attach();
        } catch (IOException e) {
          LOG.warn("Cannot open the debug log file: {}", LogText.oneLine(e.getMessage()));
        }
      }
      return new Gui(request, config, preferences, err, null, logFile).run();
    } catch (IOException e) {
      err.println("qcaret: " + LogText.oneLine(e.getMessage()));
      return EXIT_USAGE;
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
