package io.github.ajdailey.qcaret.dist;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.ajdailey.qcaret.core.testkit.TempTrees;
import io.github.ajdailey.qcaret.core.text.TextEncoding;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runs the packaged artifacts as a user would. Needs a display; the window assertions need X11
 * (Xvfb). Each launch uses its own settings directory, so natives are cached inside it.
 */
@Tag("swt")
class SmokeTest {
  private static final long BOUND_SECONDS = 60;
  private static final Pattern OPENED =
      Pattern.compile("Main window open: client area (\\d+)x(\\d+), location (-?\\d+),(-?\\d+)");

  @TempDir Path temp;

  private static Path jar() {
    return Path.of(System.getProperty("qcaret.jar"));
  }

  private static String java() {
    return Path.of(System.getProperty("java.home"), "bin", "java").toString();
  }

  /** A started process whose merged output is read into a queue on a reader thread. */
  private static final class Run implements AutoCloseable {
    final Process process;
    final LinkedBlockingQueue<String> lines = new LinkedBlockingQueue<String>();
    final List<String> seen = new ArrayList<String>();

    Run(List<String> command, Map<String, String> env, boolean clearEnv) throws IOException {
      ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
      if (clearEnv) {
        builder.environment().clear();
      }
      builder.environment().putAll(env);
      process = builder.start();
      final InputStream in = process.getInputStream();
      Thread reader =
          new Thread(
              new Runnable() {
                @Override
                public void run() {
                  try (BufferedReader r =
                      new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                    String line = r.readLine();
                    while (line != null) {
                      lines.add(line);
                      line = r.readLine();
                    }
                  } catch (IOException e) {
                    lines.add("read failed: " + e);
                  }
                }
              },
              "smoke-reader");
      reader.setDaemon(true);
      reader.start();
    }

    /** Returns the first line matching the pattern, or null after the bound. */
    Matcher await(Pattern pattern) throws InterruptedException {
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(BOUND_SECONDS);
      while (System.nanoTime() < deadline) {
        String line = lines.poll(100, TimeUnit.MILLISECONDS);
        if (line != null) {
          seen.add(line);
          Matcher m = pattern.matcher(line);
          if (m.find()) {
            return m;
          }
        } else if (!process.isAlive() && lines.isEmpty()) {
          return null;
        }
      }
      return null;
    }

    @Override
    public void close() throws InterruptedException {
      process.destroy();
      if (!process.waitFor(BOUND_SECONDS, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        process.waitFor(BOUND_SECONDS, TimeUnit.SECONDS);
      }
    }
  }

  private static List<String> windowArgs(Path settings, Path log) {
    List<String> args = new ArrayList<String>();
    args.add("-m");
    args.add("-d");
    args.add("1");
    args.add("--settings");
    args.add(settings.toString());
    args.add("--window-width");
    args.add("900");
    args.add("--window-height");
    args.add("600");
    args.add("--window-x");
    args.add("50");
    args.add("--window-y");
    args.add("40");
    args.add(log.toString());
    return args;
  }

  private static void assertOpened(Run run) throws InterruptedException {
    Matcher m = run.await(OPENED);
    assertNotNull(m, "no window line in " + run.seen);
    assertEquals("900", m.group(1));
    assertEquals("600", m.group(2));
    assertEquals("50", m.group(3));
    assertEquals("40", m.group(4));
  }

  @Test
  void versionComesFromTheManifest() throws Exception {
    List<String> command = List.of(java(), "-jar", jar().toString(), "-v");
    try (Run run = new Run(command, Map.of(), false)) {
      assertNotNull(run.await(Pattern.compile("^qcaret ")), run.seen.toString());
      // First line: LogManager prints an error first when it cannot load RetainingLogManager.
      assertEquals("qcaret " + System.getProperty("qcaret.version"), run.seen.get(0));
      assertTrue(run.process.waitFor(BOUND_SECONDS, TimeUnit.SECONDS));
      assertEquals(0, run.process.exitValue());
    }
  }

  @Test
  void universalJarOpensTheWindow() throws Exception {
    Path log = Files.writeString(temp.resolve("notes.txt"), "mock line\n");
    List<String> command = new ArrayList<String>();
    command.add(java());
    command.add("-jar");
    command.add(jar().toString());
    command.addAll(windowArgs(temp.resolve("settings"), log));
    try (Run run = new Run(command, Map.of(), false)) {
      assertOpened(run);
    }
    Path settings = temp.resolve("settings");
    assertTrue(Files.isDirectory(settings.resolve("cache").resolve("swt")));
    assertFalse(Files.exists(settings.resolve("logs")), "a run without -l wrote a logs folder");
    TempTrees.delete(settings, Duration.ofSeconds(BOUND_SECONDS));
  }

  /**
   * The Encoding menu's charsets. Windows and macOS keep the legacy ones in {@code jdk.charsets};
   * Linux's {@code java.base} carries most of them, so the module list is checked too.
   */
  static final List<String> ENCODINGS = TextEncoding.MENU;

  /**
   * With {@code -l} and {@code -m}, the logs folder holds one {@code qcaret-<pid>.log} and no lock
   * file, and the shutdown hook's line after SIGTERM reached it.
   */
  private static void assertOnlyTheLogFile(Path logs) throws IOException {
    List<Path> files = new ArrayList<Path>();
    try (DirectoryStream<Path> entries = Files.newDirectoryStream(logs)) {
      for (Path entry : entries) {
        files.add(entry);
      }
    }
    assertEquals(1, files.size(), files.toString());
    Path file = files.get(0);
    assertTrue(file.getFileName().toString().matches("qcaret-[0-9]+\\.log"), file.toString());
    List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
    System.out.println("Log file " + file.getFileName() + ", first lines:");
    for (int i = 0; i < Math.min(5, lines.size()); i++) {
      System.out.println("  " + lines.get(i));
    }
    boolean hook = false;
    for (String line : lines) {
      hook |= line.contains("Exit requested by the system; quitting");
    }
    assertTrue(hook, "no shutdown hook line in " + file);
  }

  @Test
  void linuxBundleRunsWithoutASystemJava() throws Exception {
    String target = System.getProperty("qcaret.target.name");
    assumeTrue("linux-x86_64".equals(target), "the host bundle is " + target);
    Path archive =
        Path.of(System.getProperty("qcaret.dist.dir"))
            .resolve("qcaret-" + System.getProperty("qcaret.version") + "-linux-x86_64.tar.gz");
    assertTrue(Files.isRegularFile(archive), archive.toString());
    Path unpacked = Files.createDirectory(temp.resolve("unpacked"));
    Process tar =
        new ProcessBuilder("tar", "-xzf", archive.toString(), "-C", unpacked.toString())
            .inheritIO()
            .start();
    assertTrue(tar.waitFor(BOUND_SECONDS, TimeUnit.SECONDS));
    assertEquals(0, tar.exitValue());

    // PATH holds only what bin/qcaret runs besides java.
    Path bin = Files.createDirectory(temp.resolve("path"));
    Files.createSymbolicLink(bin.resolve("uname"), Path.of("/usr/bin/uname"));
    Files.createSymbolicLink(bin.resolve("readlink"), Path.of("/usr/bin/readlink"));
    Path home = unpacked.resolve("qcaret");
    List<String> command = new ArrayList<String>();
    command.add(home.resolve("bin").resolve("qcaret").toString());
    Path log = Files.writeString(temp.resolve("bundle.txt"), "mock line\n");
    command.add("-l");
    command.addAll(windowArgs(temp.resolve("bundle-settings"), log));
    Map<String, String> env = new HashMap<String, String>();
    env.put("PATH", bin.toString());
    env.put("GDK_BACKEND", "x11");
    env.put("HOME", temp.toString());
    for (String name : List.of("DISPLAY", "XAUTHORITY")) {
      String value = System.getenv(name);
      if (value != null) {
        env.put(name, value);
      }
    }
    try (Run run = new Run(command, env, true)) {
      Matcher java = run.await(Pattern.compile("on Java (\\S+) from (\\S+)"));
      assertNotNull(java, run.seen.toString());
      assertEquals(home.resolve("runtime").toRealPath().toString(), java.group(2));
      assertOpened(run);
    }
    assertOnlyTheLogFile(temp.resolve("bundle-settings").resolve("logs"));

    String release = Files.readString(home.resolve("runtime").resolve("release"));
    assertTrue(release.contains("jdk.charsets"), release);
    List<String> probe = new ArrayList<String>();
    probe.add(home.resolve("runtime").resolve("bin").resolve("java").toString());
    probe.add("-cp");
    probe.add(
        Path.of(CharsetProbe.class.getProtectionDomain().getCodeSource().getLocation().toURI())
            .toString());
    probe.add(CharsetProbe.class.getName());
    probe.addAll(ENCODINGS);
    try (Run run = new Run(probe, Map.of(), false)) {
      assertNotNull(run.await(Pattern.compile("^charsets missing: 0$")), run.seen.toString());
    }
  }
}
