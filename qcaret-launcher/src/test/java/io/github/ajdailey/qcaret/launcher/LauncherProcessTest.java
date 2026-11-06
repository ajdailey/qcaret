package io.github.ajdailey.qcaret.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.ipc.InstanceServer;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Launches as real processes. Do not move these into one JVM: on Linux and macOS the lock of one
 * process never conflicts with itself, so only {@code sun.nio.ch.FileLockTable} arbitrates, and
 * threads closing channels on the lock file can drop a live entry from it and let a second claim
 * succeed.
 */
class LauncherProcessTest {
  private static final long BOUND_SECONDS = 60;
  private static final String END = "end of output";

  @TempDir Path temp;

  /** One {@link LaunchMain}. */
  private static final class Child {
    private final Process process;
    private final BufferedWriter in;
    private final BlockingQueue<String> lines = new LinkedBlockingQueue<String>();

    Child(Path directory, Workers workers) throws IOException {
      process =
          new ProcessBuilder(
                  Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                  "-XX:TieredStopAtLevel=1",
                  "-cp",
                  System.getProperty("java.class.path"),
                  LaunchMain.class.getName(),
                  directory.toString())
              .redirectError(ProcessBuilder.Redirect.DISCARD)
              .start();
      in =
          new BufferedWriter(
              new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
      workers.start(
          Workers.Kind.IPC,
          new Runnable() {
            @Override
            public void run() {
              readOutput();
            }
          });
    }

    private void readOutput() {
      try (BufferedReader out =
          new BufferedReader(
              new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
        String line = out.readLine();
        while (line != null) {
          lines.add(line);
          line = out.readLine();
        }
      } catch (IOException e) {
        lines.add(e.toString());
      }
      lines.add(END);
    }

    void send() throws IOException {
      in.newLine();
      in.flush();
    }

    /**
     * Returns the first line starting with {@code prefix}, without the prefix; logs are skipped.
     */
    String await(String prefix) throws InterruptedException {
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(BOUND_SECONDS);
      while (true) {
        String line = lines.poll(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
        if (line == null || END.equals(line)) {
          return "missing: " + prefix;
        }
        if (line.startsWith(prefix)) {
          return line.substring(prefix.length());
        }
      }
    }

    void end() throws InterruptedException {
      process.destroyForcibly();
      assertTrue(process.waitFor(BOUND_SECONDS, TimeUnit.SECONDS));
    }
  }

  @Test
  void sixteenLaunchesOfAnEndingPrimaryLeaveOnePrimaryAndTheRestHanded() throws Exception {
    int launches = 16;
    List<Child> children = new ArrayList<Child>();
    try (Workers workers = new Workers(1, new Launcher.FailureLogger())) {
      try {
        for (int i = 0; i < launches; i++) {
          children.add(new Child(temp, workers));
        }
        for (Child child : children) {
          assertEquals("", child.await(LaunchMain.READY));
        }
        List<String> outcomes = new ArrayList<String>();
        Child primary = null;
        try (InstanceServer ending = InstanceServer.claim(temp)) {
          assertNotNull(ending);
          assertTrue(ending.start(workers));
          for (Child child : children) {
            child.send();
          }
          Thread.sleep(200);
        }
        for (Child child : children) {
          String outcome = child.await(LaunchMain.OUTCOME);
          outcomes.add(outcome);
          if (Launcher.Handoff.PRIMARY.name().equals(outcome)) {
            primary = child;
          }
        }
        String primaries = Launcher.Handoff.PRIMARY.name();
        assertEquals(1, Collections.frequency(outcomes, primaries), outcomes.toString());
        assertEquals(
            launches - 1,
            Collections.frequency(outcomes, Launcher.Handoff.HANDED.name()),
            outcomes.toString());
        primary.send();
        assertEquals(String.valueOf(launches - 1), primary.await(LaunchMain.RECEIVED));
      } finally {
        for (Child child : children) {
          child.end();
        }
      }
    }
  }
}
