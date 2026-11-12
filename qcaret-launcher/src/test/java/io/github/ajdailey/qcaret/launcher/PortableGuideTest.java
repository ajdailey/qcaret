package io.github.ajdailey.qcaret.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.config.SettingsLocation;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * User Guide in a portable run on GTK 3 creates no browser, so WebKitGTK and Mesa write nothing
 * under the home directory. The child process has its own {@code HOME}, no XDG base directory
 * variables, and an {@code XDG_DATA_DIRS} whose entries mirror the system ones without {@code
 * applications}, so no application opens the guide and the notice names it. GLib reads {@code HOME}
 * from the environment, which is why the run is a separate process. The empty {@code
 * .config/ibus/bus} the GTK input-method module creates is allowed.
 */
@Tag("swt")
@EnabledOnOs(OS.LINUX)
@DisabledIfEnvironmentVariable(named = "SWT_GTK4", matches = "1")
class PortableGuideTest {
  private static final String NATIVE_ACCESS = "--enable-native-access=ALL-UNNAMED";
  private static final long EXIT_SECONDS = 120;

  @TempDir Path temp;

  @Test
  void theGuideInAPortableRunWritesNothingUnderTheHome() throws Exception {
    Path bundle = Files.createDirectories(temp.resolve("bundle"));
    Files.createFile(bundle.resolve(SettingsLocation.MARKER));
    Path home = Files.createDirectories(temp.resolve("home"));
    List<Path> links = new ArrayList<Path>();
    Path out = temp.resolve("out.txt");
    Path errors = temp.resolve("err.txt");
    ProcessBuilder builder =
        new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                NATIVE_ACCESS,
                "-Duser.home=" + home,
                "-cp",
                System.getProperty("java.class.path"),
                PortableGuideMain.class.getName(),
                bundle.toString())
            .redirectOutput(out.toFile())
            .redirectError(errors.toFile());
    Map<String, String> env = builder.environment();
    env.put("HOME", home.toString());
    env.put("XDG_DATA_DIRS", withoutApplications(temp.resolve("data-dirs"), links));
    env.put("GDK_BACKEND", "x11");
    env.remove("XDG_CACHE_HOME");
    env.remove("XDG_DATA_HOME");
    env.remove("XDG_CONFIG_HOME");
    Process child = builder.start();
    boolean exited;
    try {
      exited = child.waitFor(EXIT_SECONDS, TimeUnit.SECONDS);
    } finally {
      child.destroyForcibly();
      child.waitFor(10, TimeUnit.SECONDS);
      for (Path link : links) {
        Files.delete(link);
      }
    }
    String output = Files.readString(out, StandardCharsets.UTF_8);
    String errorText = Files.readString(errors, StandardCharsets.UTF_8);
    assertTrue(exited, "child did not exit; stderr:\n" + errorText);
    assertEquals(0, child.exitValue(), "stderr:\n" + errorText);
    assertTrue(
        output.contains(PortableGuideMain.OUTCOME + PortableGuideMain.NOTICE),
        output + "\nstderr:\n" + errorText);
    String listing = String.join("\n", entries(home));
    assertFalse(Files.exists(home.resolve(".cache").resolve("QCaret")), listing);
    assertFalse(Files.exists(home.resolve(".local").resolve("share").resolve("QCaret")), listing);
    assertFalse(Files.exists(home.resolve(".cache").resolve("mesa_shader_cache")), listing);
  }

  /**
   * Mirrors each system data directory, in order, as a folder of links to its entries except {@code
   * applications}, so GTK finds the same MIME database, icons and schemas but GIO finds no HTML
   * handler. One folder per directory keeps the search order: a partial {@code
   * /usr/local/share/mime} must not hide {@code /usr/share/mime}.
   *
   * @param root the folder the mirrors go in
   * @param links receives every link made, for deletion after the run
   * @return the {@code XDG_DATA_DIRS} value
   */
  private static String withoutApplications(Path root, List<Path> links) throws IOException {
    String dirs = System.getenv("XDG_DATA_DIRS");
    if (dirs == null || dirs.isEmpty()) {
      dirs = "/usr/local/share:/usr/share";
    }
    List<String> mirrors = new ArrayList<String>();
    for (String dir : dirs.split(":")) {
      if (dir.isEmpty() || !Files.isDirectory(Path.of(dir))) {
        continue;
      }
      Path mirror = Files.createDirectories(root.resolve(Integer.toString(mirrors.size())));
      mirrors.add(mirror.toString());
      try (DirectoryStream<Path> children = Files.newDirectoryStream(Path.of(dir))) {
        for (Path child : children) {
          String name = child.getFileName().toString();
          if (!"applications".equals(name)) {
            links.add(Files.createSymbolicLink(mirror.resolve(name), child));
          }
        }
      }
    }
    return String.join(":", mirrors);
  }

  /** Returns every file and directory under the root, relative to it. */
  private static List<String> entries(final Path root) throws IOException {
    final List<String> found = new ArrayList<String>();
    Files.walkFileTree(
        root,
        new SimpleFileVisitor<Path>() {
          @Override
          public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes) {
            found.add(root.relativize(dir).toString());
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
            found.add(root.relativize(file).toString());
            return FileVisitResult.CONTINUE;
          }
        });
    return found;
  }
}
