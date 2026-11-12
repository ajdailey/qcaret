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
import java.nio.file.LinkOption;
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
 * variables, and an {@code XDG_DATA_DIRS} without {@code applications}, so no application opens the
 * guide and the notice names it. GLib reads {@code HOME} from the environment, which is why the run
 * is a separate process. The empty {@code .config/ibus/bus} the GTK input-method module creates is
 * allowed.
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
    Path dataDirs = withoutApplications(Files.createDirectories(temp.resolve("data-dirs")));
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
    env.put("XDG_DATA_DIRS", dataDirs.toString());
    env.put("GDK_BACKEND", "x11");
    env.remove("XDG_CACHE_HOME");
    env.remove("XDG_DATA_HOME");
    env.remove("XDG_CONFIG_HOME");
    Process child = builder.start();
    try {
      assertTrue(child.waitFor(EXIT_SECONDS, TimeUnit.SECONDS), "child did not exit");
    } finally {
      child.destroyForcibly();
      child.waitFor(10, TimeUnit.SECONDS);
    }
    String errorText = Files.readString(errors, StandardCharsets.UTF_8);
    assertEquals(0, child.exitValue(), errorText);
    assertTrue(
        Files.readString(out, StandardCharsets.UTF_8)
            .contains(PortableGuideMain.OUTCOME + PortableGuideMain.NOTICE),
        Files.readString(out, StandardCharsets.UTF_8) + errorText);
    String listing = String.join("\n", entries(home));
    assertFalse(Files.exists(home.resolve(".cache").resolve("QCaret")), listing);
    assertFalse(Files.exists(home.resolve(".local").resolve("share").resolve("QCaret")), listing);
    assertFalse(Files.exists(home.resolve(".cache").resolve("mesa_shader_cache")), listing);
  }

  /**
   * Links every entry of the system data directories into the folder except {@code applications},
   * so GTK finds its MIME database, icons and schemas but GIO finds no HTML handler.
   */
  private static Path withoutApplications(Path folder) throws IOException {
    String dirs = System.getenv("XDG_DATA_DIRS");
    if (dirs == null || dirs.isEmpty()) {
      dirs = "/usr/local/share:/usr/share";
    }
    for (String dir : dirs.split(":")) {
      if (dir.isEmpty() || !Files.isDirectory(Path.of(dir))) {
        continue;
      }
      try (DirectoryStream<Path> children = Files.newDirectoryStream(Path.of(dir))) {
        for (Path child : children) {
          String name = child.getFileName().toString();
          Path link = folder.resolve(name);
          if (!"applications".equals(name) && !Files.exists(link, LinkOption.NOFOLLOW_LINKS)) {
            Files.createSymbolicLink(link, child);
          }
        }
      }
    }
    return folder;
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
