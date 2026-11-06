package io.github.ajdailey.qcaret.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.ipc.InstanceServer;
import io.github.ajdailey.qcaret.core.io.SafePaths;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * A real process whose handoff fails while this JVM holds the lock: it opens a window, restores and
 * saves no session, and saves no placement. Linux only for {@code SigtermTest}'s reason: the
 * ordered quit needs SIGTERM.
 */
@Tag("swt")
@EnabledOnOs(OS.LINUX)
class BesideRunTest {
  private static final long START_NANOS = TimeUnit.SECONDS.toNanos(60);
  private static final long EXIT_MARGIN_MILLIS = 2000;

  @TempDir Path temp;

  @Test
  void aFailedHandoffUnderAHeldLockRunsWithoutTheSession() throws Exception {
    String java = ProcessHandle.current().info().command().orElse(null);
    if (java == null) {
      return;
    }
    Path settings = SafePaths.settingsDirectory(temp.resolve("settings"));
    Path home = Files.createDirectories(temp.resolve("home"));
    Path session = settings.resolve("qcaret_session.conf");
    String saved = "recent.0=" + temp.resolve("mock.txt") + "\nversion=1\n";
    Files.writeString(session, saved, StandardCharsets.ISO_8859_1);
    try (InstanceServer held = InstanceServer.claim(settings)) {
      assertNotNull(held);
      Files.delete(settings.resolve("instance.endpoint"));
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
        while (!read(log).contains("Main window open")
            && child.isAlive()
            && System.nanoTime() < deadline) {
          Thread.sleep(50);
        }
        assertTrue(read(log).contains("Main window open"), "no window; log: " + read(log));
        child.destroy();
        assertTrue(
            child.waitFor(
                ShutdownHook.BOUND.toMillis() + EXIT_MARGIN_MILLIS, TimeUnit.MILLISECONDS),
            "not ended within the hook's bound");
      } finally {
        child.destroyForcibly();
        child.waitFor(10, TimeUnit.SECONDS);
      }
      assertEquals(saved, read(session));
      assertFalse(read(settings.resolve("qcaret.conf")).contains("window.width="));
    }
  }

  private static String read(Path file) throws IOException {
    return Files.exists(file) ? Files.readString(file, StandardCharsets.ISO_8859_1) : "";
  }
}
