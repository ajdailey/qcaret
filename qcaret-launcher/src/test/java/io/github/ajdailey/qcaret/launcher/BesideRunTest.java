package io.github.ajdailey.qcaret.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.ajdailey.qcaret.app.config.SettingsLocation;
import io.github.ajdailey.qcaret.app.config.SettingsMode;
import io.github.ajdailey.qcaret.app.ipc.InstanceServer;
import io.github.ajdailey.qcaret.app.session.SessionLock;
import io.github.ajdailey.qcaret.core.io.SafePaths;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * A real process whose handoff fails while this JVM holds the instance lock, and one that runs
 * alone while this JVM holds the session lock: each opens a window, restores and saves no session,
 * and saves no placement. Linux only for {@code SigtermTest}'s reason: the ordered quit needs
 * SIGTERM.
 */
@Tag("swt")
@EnabledOnOs(OS.LINUX)
class BesideRunTest {
  private static final long START_NANOS = TimeUnit.SECONDS.toNanos(60);
  private static final long EXIT_MARGIN_MILLIS = 2000;

  @TempDir Path temp;

  @Test
  void aFailedHandoffUnderAHeldLockRunsWithoutTheSession() throws Exception {
    Path settings = SafePaths.settingsDirectory(temp.resolve("settings"));
    String saved = writeSession(settings);
    try (InstanceServer held = InstanceServer.claim(settings)) {
      assertNotNull(held);
      Files.delete(settings.resolve("instance.endpoint"));
      runAndEnd(settings, "Main window open");
      assertEquals(saved, read(settings.resolve("qcaret_session.conf")));
      assertFalse(read(settings.resolve("qcaret.conf")).contains("window.width="));
    }
  }

  /** A group-writable settings directory turns the handoff off, so the launch runs alone. */
  @Test
  void aLaunchRunningAloneWhileAnotherProcessOwnsTheSessionRunsWithoutIt() throws Exception {
    Path settings = SafePaths.settingsDirectory(temp.resolve("settings"));
    Files.setPosixFilePermissions(settings, PosixFilePermissions.fromString("rwxrwx---"));
    String saved = writeSession(settings);
    try (SessionLock held =
        SessionLock.acquire(new SettingsLocation(SettingsMode.EXPLICIT, settings))) {
      assertNotNull(held);
      runAndEnd(settings, "this one runs without it");
      assertFalse(Files.exists(settings.resolve("instance.lock")));
      assertEquals(saved, read(settings.resolve("qcaret_session.conf")));
      assertFalse(read(settings.resolve("qcaret.conf")).contains("window.width="));
    }
  }

  private String writeSession(Path settings) throws IOException {
    String saved = "recent.0=" + temp.resolve("mock.txt") + "\nversion=1\n";
    Files.writeString(settings.resolve("qcaret_session.conf"), saved, StandardCharsets.ISO_8859_1);
    return saved;
  }

  /**
   * Starts a launch, waits for its window and a log line, then ends it through SIGTERM.
   *
   * @return the launch's log
   */
  private String runAndEnd(Path settings, String line) throws Exception {
    String java = ProcessHandle.current().info().command().orElse(null);
    assumeTrue(java != null, "no java command");
    Path home = Files.createDirectories(temp.resolve("home"));
    Process child =
        new ProcessBuilder(
                java,
                "-Duser.home=" + home,
                "-cp",
                System.getProperty("java.class.path"),
                Main.class.getName(),
                "--settings",
                settings.toString(),
                "-l")
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start();
    Path log = settings.resolve("logs").resolve("qcaret-" + child.pid() + ".log");
    try {
      long deadline = System.nanoTime() + START_NANOS;
      while (!(read(log).contains("Main window open") && read(log).contains(line))
          && child.isAlive()
          && System.nanoTime() < deadline) {
        Thread.sleep(50);
      }
      assertTrue(read(log).contains("Main window open"), "no window; log: " + read(log));
      assertTrue(read(log).contains(line), "no '" + line + "'; log: " + read(log));
      child.destroy();
      assertTrue(
          child.waitFor(ShutdownHook.BOUND.toMillis() + EXIT_MARGIN_MILLIS, TimeUnit.MILLISECONDS),
          "not ended within the hook's bound");
    } finally {
      child.destroyForcibly();
      child.waitFor(10, TimeUnit.SECONDS);
    }
    return read(log);
  }

  private static String read(Path file) throws IOException {
    return Files.exists(file) ? Files.readString(file, StandardCharsets.ISO_8859_1) : "";
  }
}
