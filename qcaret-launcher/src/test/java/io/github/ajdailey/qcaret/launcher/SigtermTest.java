package io.github.ajdailey.qcaret.launcher;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
 * SIGTERM to a running QCaret process: the shutdown hook runs the quit, which saves the window
 * placement, and the process ends within the hook's bound. Linux only: {@code Process.destroy}
 * sends SIGTERM there, while on Windows it terminates the process without running hooks.
 */
@Tag("swt")
@EnabledOnOs(OS.LINUX)
class SigtermTest {
  private static final long START_NANOS = TimeUnit.SECONDS.toNanos(60);
  private static final long EXIT_MARGIN_MILLIS = 2000;

  @TempDir Path temp;

  @Test
  void sigtermSavesTheWindowPlacement() throws Exception {
    String java = ProcessHandle.current().info().command().orElse(null);
    if (java == null) {
      return;
    }
    Path settings = Files.createDirectory(temp.resolve("settings"));
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
    Path conf = settings.resolve("qcaret.conf");
    try {
      long deadline = System.nanoTime() + START_NANOS;
      while (!read(log).contains("Main window open")
          && child.isAlive()
          && System.nanoTime() < deadline) {
        Thread.sleep(50);
      }
      assertTrue(child.isAlive(), "QCaret ended before SIGTERM");
      assertTrue(read(log).contains("Main window open"), "no window; log: " + read(log));
      assertFalse(Files.exists(conf), "settings written before the quit");
      long sent = System.nanoTime();
      child.destroy();
      assertTrue(
          child.waitFor(ShutdownHook.BOUND.toMillis() + EXIT_MARGIN_MILLIS, TimeUnit.MILLISECONDS),
          "not ended within the hook's bound");
      System.out.println(
          "SIGTERM to exit: " + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - sent) + " ms");
    } finally {
      child.destroyForcibly();
      child.waitFor(10, TimeUnit.SECONDS);
    }
    String saved = read(conf);
    assertTrue(saved.contains("window.width="), "placement not saved: " + saved);
  }

  private static String read(Path file) throws IOException {
    return Files.exists(file) ? Files.readString(file, StandardCharsets.ISO_8859_1) : "";
  }
}
