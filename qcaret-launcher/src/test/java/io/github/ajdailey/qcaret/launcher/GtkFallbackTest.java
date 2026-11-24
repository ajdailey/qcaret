package io.github.ajdailey.qcaret.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.ui.swt.platform.PlatformTraits;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code SWT_GTK4=1} with GTK 4 unloadable: an empty {@code libgtk-4.so.1} first on {@code
 * LD_LIBRARY_PATH} makes SWT load GTK 3, and a child JVM's {@code PlatformTraits.PLATFORM} must
 * answer as GTK 3 and log the fallback.
 */
@Tag("swt")
@EnabledOnOs(OS.LINUX)
class GtkFallbackTest {
  private static final long EXIT_SECONDS = 60;

  @TempDir Path temp;

  @Test
  void gtk4FallbackTakesGtk3Traits() throws Exception {
    String java = ProcessHandle.current().info().command().orElse(null);
    if (java == null) {
      return;
    }
    Path libs = Files.createDirectories(temp.resolve("libs"));
    Files.createFile(libs.resolve("libgtk-4.so.1"));
    Path out = temp.resolve("out.txt");
    ProcessBuilder builder =
        new ProcessBuilder(
                java, "-cp", System.getProperty("java.class.path"), Probe.class.getName())
            .redirectErrorStream(true)
            .redirectOutput(out.toFile());
    Map<String, String> env = builder.environment();
    env.put("SWT_GTK4", "1");
    String path = env.get("LD_LIBRARY_PATH");
    env.put("LD_LIBRARY_PATH", path == null ? libs.toString() : libs + ":" + path);
    Process child = builder.start();
    try {
      assertTrue(child.waitFor(EXIT_SECONDS, TimeUnit.SECONDS), "probe did not end");
    } finally {
      child.destroyForcibly();
      child.waitFor(10, TimeUnit.SECONDS);
    }
    String text = Files.readString(out, StandardCharsets.UTF_8);
    assertEquals(0, child.exitValue(), text);
    assertTrue(text.contains("toolkit=3."), text);
    assertTrue(
        text.contains(
            "caretBlend=DIFFERENCE browser=Gtk3BrowserBehavior print=Gtk3PrintSupport"
                + " sizing=NativeToolItemSizing"),
        text);
    assertTrue(
        text.contains("SWT_GTK4=1 is set but GTK 4 failed to load; running on GTK 3."), text);
  }

  @Test
  void noLoadableGtkEndsWithTheSwtStatus() throws Exception {
    String java = ProcessHandle.current().info().command().orElse(null);
    if (java == null) {
      return;
    }
    Path libs = Files.createDirectories(temp.resolve("nogtk"));
    Files.createFile(libs.resolve("libgtk-4.so.1"));
    Files.createFile(libs.resolve("libgtk-3.so.0"));
    Path out = temp.resolve("nogtk.txt");
    ProcessBuilder builder =
        new ProcessBuilder(
                java,
                "-Duser.home=" + Files.createDirectories(temp.resolve("home")),
                "-cp",
                System.getProperty("java.class.path"),
                Main.class.getName(),
                "-m",
                "--settings",
                temp.resolve("settings").toString())
            .redirectErrorStream(true)
            .redirectOutput(out.toFile());
    Map<String, String> env = builder.environment();
    env.remove("SWT_GTK4");
    String path = env.get("LD_LIBRARY_PATH");
    env.put("LD_LIBRARY_PATH", path == null ? libs.toString() : libs + ":" + path);
    Process child = builder.start();
    try {
      assertTrue(child.waitFor(EXIT_SECONDS, TimeUnit.SECONDS), "launcher did not end");
    } finally {
      child.destroyForcibly();
      child.waitFor(10, TimeUnit.SECONDS);
    }
    String text = Files.readString(out, StandardCharsets.UTF_8);
    assertEquals(Launcher.EXIT_SWT, child.exitValue(), text);
    assertTrue(text.contains("qcaret: SWT failed to start"), text);
  }

  /** Prints the running traits' GTK 3 and GTK 4 differences. */
  public static final class Probe {
    private Probe() {}

    /**
     * Prints the answers.
     *
     * @param args unused
     */
    public static void main(String[] args) {
      PlatformTraits traits = PlatformTraits.PLATFORM;
      System.out.println(
          "toolkit="
              + traits.toolkitVersion()
              + " caretBlend="
              + traits.caretBlend()
              + " browser="
              + traits.browserBehavior().getClass().getSimpleName()
              + " print="
              + traits.printSupport().getClass().getSimpleName()
              + " sizing="
              + traits.toolItemSizing().getClass().getSimpleName());
    }
  }
}
