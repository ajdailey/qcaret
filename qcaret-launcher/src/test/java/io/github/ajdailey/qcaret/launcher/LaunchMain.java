package io.github.ajdailey.qcaret.launcher;

import io.github.ajdailey.qcaret.app.cli.FileRequest;
import io.github.ajdailey.qcaret.app.cli.StartupRequest;
import io.github.ajdailey.qcaret.app.ipc.InstanceServer;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A child process for {@code LauncherProcessTest}: one launch through {@link Launcher#handOff}.
 * Prints {@link #READY}, waits for a line on standard input, then prints {@link #OUTCOME} and the
 * result. A {@code PRIMARY} takes launches until the next line or end of input, closes and prints
 * {@link #RECEIVED} and the count.
 */
public final class LaunchMain {
  static final String READY = "ready";
  static final String OUTCOME = "outcome ";
  static final String RECEIVED = "received ";

  private LaunchMain() {}

  /**
   * Runs one launch.
   *
   * @param args the settings directory
   * @throws IOException when standard input fails
   */
  public static void main(String[] args) throws IOException {
    Path directory = Path.of(args[0]);
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
      in.readLine();
    }
    say(RECEIVED + received.get());
  }

  private static void say(String line) {
    System.out.println(line);
    System.out.flush();
  }
}
