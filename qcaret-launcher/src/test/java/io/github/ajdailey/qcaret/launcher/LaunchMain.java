package io.github.ajdailey.qcaret.launcher;

import io.github.ajdailey.qcaret.app.cli.FileRequest;
import io.github.ajdailey.qcaret.app.cli.StartupRequest;
import io.github.ajdailey.qcaret.app.ipc.InstanceServer;
import io.github.ajdailey.qcaret.app.recovery.RecoveryFile;
import io.github.ajdailey.qcaret.app.recovery.RecoveryStore;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.document.DocumentFiles;
import io.github.ajdailey.qcaret.core.document.LoadOptions;
import io.github.ajdailey.qcaret.core.io.ContentWriter;
import io.github.ajdailey.qcaret.core.io.FileStamp;
import io.github.ajdailey.qcaret.core.io.SafeSave;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A child process for {@code LauncherProcessTest}: one launch through {@link Launcher#handOff}.
 * Prints {@link #READY}, waits for a line on standard input, then prints {@link #OUTCOME} and the
 * result. A {@code PRIMARY} holds a recovery store and takes launches. On a {@link #READ} line it
 * reads every file in the settings directory and prints {@link #READ} and the count; on a {@link
 * #SAVE} line it saves over {@code instance.lock} as a document save does and prints {@link #SAVE}
 * and {@link #REFUSED} or {@link #SAVED}; a {@link #SAVE_OWNER} line does that to the recovery
 * store's {@code owner.lock}; on any other line or end of input it closes and prints {@link
 * #RECEIVED} and the count.
 *
 * <p>With a second argument {@link #CLAIM} it opens a recovery store of its own, as a {@code -m}
 * start does, and prints {@link #CLAIM} and the buffers of the instances it finds dead.
 *
 * <p>With a second argument {@link #HOLD} it claims the lock, starts the listener, prints {@link
 * #HOLDING} and never delivers, so every handoff stays open until the test kills it.
 */
public final class LaunchMain {
  static final String READY = "ready";
  static final String OUTCOME = "outcome ";
  static final String RECEIVED = "received ";
  static final String READ = "read ";
  static final String SAVE = "save ";
  static final String SAVE_OWNER = "saveowner ";
  static final String CLAIM = "claim ";
  static final String REFUSED = "refused";
  static final String SAVED = "saved";
  static final String HOLD = "hold";
  static final String HOLDING = "holding";

  private LaunchMain() {}

  /**
   * Runs one launch.
   *
   * @param args the settings directory, then optionally {@link #HOLD}
   * @throws IOException when standard input fails
   */
  public static void main(String[] args) throws IOException {
    Path directory = Path.of(args[0]);
    if (args.length > 1) {
      if (CLAIM.strip().equals(args[1])) {
        claim(directory);
      } else {
        hold(directory);
      }
      return;
    }
    BufferedReader in =
        new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
    say(READY);
    if (in.readLine() == null) {
      return;
    }
    StartupRequest request =
        StartupRequest.handoff(
            List.of(new FileRequest(directory.resolve("mock.txt"))),
            false,
            null,
            null,
            null,
            null,
            null,
            null);
    Launcher launcher = new Launcher(System.out, System.err);
    Launcher.Handoff outcome = launcher.handOff(directory, request);
    say(OUTCOME + outcome);
    if (outcome != Launcher.Handoff.PRIMARY) {
      return;
    }
    final AtomicInteger received = new AtomicInteger();
    RecoveryStore recovery = new RecoveryStore(directory, "live");
    recovery.open();
    Path live = directory.resolve(RecoveryStore.DIRECTORY).resolve("live");
    Files.writeString(live.resolve("mock" + RecoveryFile.SUFFIX), "mock");
    try (Workers workers = new Workers(1, new Launcher.FailureLogger());
        InstanceServer server = launcher.primary()) {
      server.start(workers);
      server.deliverTo(
          new InstanceServer.Receiver() {
            @Override
            public boolean deliver(StartupRequest launch) {
              received.incrementAndGet();
              return true;
            }
          });
      String line = in.readLine();
      while (true) {
        if (READ.strip().equals(line)) {
          say(READ + readAll(directory));
        } else if (SAVE.strip().equals(line)) {
          say(SAVE + saveLock(directory.resolve("instance.lock")));
        } else if (SAVE_OWNER.strip().equals(line)) {
          say(SAVE_OWNER + saveLock(live.resolve(RecoveryStore.LOCK)));
        } else {
          break;
        }
        line = in.readLine();
      }
    }
    recovery.close(false);
    say(RECEIVED + received.get());
  }

  private static void claim(Path directory) throws IOException {
    RecoveryStore recovery = new RecoveryStore(directory, "second");
    recovery.open();
    int buffers = 0;
    for (RecoveryStore.Claim claim : recovery.claimDead(0)) {
      buffers += claim.entries().size();
      claim.close();
    }
    recovery.close(false);
    say(CLAIM + buffers);
  }

  private static String saveLock(Path lock) {
    try {
      SafeSave.save(
          lock,
          FileStamp.read(lock),
          new ContentWriter() {
            @Override
            public void write(OutputStream out) throws IOException {
              out.write('x');
            }
          },
          new CancellationToken());
      return SAVED;
    } catch (IOException e) {
      return REFUSED;
    }
  }

  private static void hold(Path directory) throws IOException {
    try (Workers workers = new Workers(1, new Launcher.FailureLogger());
        InstanceServer server = InstanceServer.claim(directory)) {
      server.start(workers);
      say(HOLDING);
      System.in.read();
    }
  }

  /**
   * Reads each file under a directory as a document open and a plain read do, closing a channel on
   * each. Windows refuses the locked region of a held file; the attempt still counts.
   *
   * @return the files attempted
   */
  private static int readAll(Path directory) throws IOException {
    int files = 0;
    try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
      for (Path entry : entries) {
        if (Files.isDirectory(entry)) {
          files += readAll(entry);
        } else {
          files++;
          try {
            DocumentFiles.estimate(entry, LoadOptions.DEFAULTS.limits());
            Files.readAllBytes(entry);
          } catch (IOException e) {
            say("unreadable " + entry.getFileName());
          }
        }
      }
    }
    return files;
  }

  private static void say(String line) {
    System.out.println(line);
    System.out.flush();
  }
}
