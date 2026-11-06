package io.github.ajdailey.qcaret.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.ipc.InstanceServer;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.io.SafePaths;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** Launches as real processes: the arbiter's contract is between processes. */
class LauncherProcessTest {
  private static final long BOUND_SECONDS = 60;
  private static final String END = "end of output";
  private static final String NATIVE_ACCESS = "--enable-native-access=ALL-UNNAMED";

  @TempDir Path temp;

  /** One {@link LaunchMain}. */
  private static final class Child {
    private final Process process;
    private final BufferedWriter in;
    private final BlockingQueue<String> lines = new LinkedBlockingQueue<String>();

    Child(Path directory, Workers workers, String... mode) throws IOException {
      this(directory, workers, NATIVE_ACCESS, ProcessBuilder.Redirect.DISCARD, mode);
    }

    Child(
        Path directory,
        Workers workers,
        String nativeAccess,
        ProcessBuilder.Redirect errors,
        String... mode)
        throws IOException {
      List<String> command = new ArrayList<String>();
      command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
      command.add(nativeAccess);
      command.add("-XX:TieredStopAtLevel=1");
      command.add("-cp");
      command.add(System.getProperty("java.class.path"));
      command.add(LaunchMain.class.getName());
      command.add(directory.toString());
      command.addAll(List.of(mode));
      process = new ProcessBuilder(command).redirectError(errors).start();
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
      send("");
    }

    void send(String line) throws IOException {
      in.write(line);
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

  /** Connections held by a killed primary fail together; one launch claims, the rest hand over. */
  @Test
  void sixteenLaunchesOfAKilledPrimaryLeaveOnePrimaryAndTheRestHanded() throws Exception {
    int launches = 16;
    List<Child> children = new ArrayList<Child>();
    try (Workers workers = new Workers(1, new Launcher.FailureLogger())) {
      Child holder = new Child(temp, workers, LaunchMain.HOLD);
      try {
        assertEquals("", holder.await(LaunchMain.HOLDING));
        for (int i = 0; i < launches; i++) {
          children.add(new Child(temp, workers));
        }
        for (Child child : children) {
          assertEquals("", child.await(LaunchMain.READY));
        }
        for (Child child : children) {
          child.send();
        }
        Thread.sleep(200);
        holder.end();
        List<String> outcomes = new ArrayList<String>();
        Child primary = null;
        for (Child child : children) {
          String outcome = child.await(LaunchMain.OUTCOME);
          outcomes.add(outcome);
          if (Launcher.Handoff.PRIMARY.name().equals(outcome)) {
            primary = child;
          }
        }
        assertEquals(
            1,
            Collections.frequency(outcomes, Launcher.Handoff.PRIMARY.name()),
            outcomes.toString());
        assertEquals(
            launches - 1,
            Collections.frequency(outcomes, Launcher.Handoff.HANDED.name()),
            outcomes.toString());
        primary.send();
        assertEquals(String.valueOf(launches - 1), primary.await(LaunchMain.RECEIVED));
      } finally {
        holder.end();
        for (Child child : children) {
          child.end();
        }
      }
    }
  }

  @Test
  void aPrimaryThatReadsEveryFileInTheSettingsDirectoryKeepsTheLock() throws Exception {
    try (Workers workers = new Workers(1, new Launcher.FailureLogger())) {
      Child primary = new Child(temp, workers);
      Child second = new Child(temp, workers);
      try {
        assertEquals("", primary.await(LaunchMain.READY));
        primary.send();
        assertEquals(Launcher.Handoff.PRIMARY.name(), primary.await(LaunchMain.OUTCOME));
        primary.send(LaunchMain.READ.strip());
        // instance.lock, instance.endpoint and recovery/live/owner.lock.
        assertEquals("3", primary.await(LaunchMain.READ));
        assertEquals("", second.await(LaunchMain.READY));
        second.send();
        assertEquals(Launcher.Handoff.HANDED.name(), second.await(LaunchMain.OUTCOME));
        primary.send();
        assertEquals("1", primary.await(LaunchMain.RECEIVED));
      } finally {
        second.end();
        primary.end();
      }
    }
  }

  /**
   * Denied native access stands for a JDK where the {@code flock} binding fails: the launch runs
   * alone with one WARN and no POSIX lock in its place.
   */
  @Test
  @DisabledOnOs(OS.WINDOWS)
  void aLaunchWithoutFlockRunsAloneWithOneWarning() throws Exception {
    Path errors = temp.resolve("errors.txt");
    Path settings = SafePaths.settingsDirectory(temp.resolve("settings"));
    try (Workers workers = new Workers(1, new Launcher.FailureLogger())) {
      Child child =
          new Child(
              settings,
              workers,
              "--enable-native-access=java.base",
              ProcessBuilder.Redirect.to(errors.toFile()));
      try {
        assertEquals("", child.await(LaunchMain.READY));
        child.send();
        assertEquals(Launcher.Handoff.ALONE.name(), child.await(LaunchMain.OUTCOME));
        assertEquals("missing: " + END, child.await(END));
      } finally {
        child.end();
      }
    }
    String log = Files.readString(errors);
    assertEquals(1, count(log, "WARNING: "), log);
    assertEquals(1, count(log, "Single-instance handoff off: flock is not available"), log);
    assertFalse(Files.exists(settings.resolve("instance.lock")));
  }

  private static int count(String text, String part) {
    int found = 0;
    int at = text.indexOf(part);
    while (at >= 0) {
      found++;
      at = text.indexOf(part, at + part.length());
    }
    return found;
  }
}
