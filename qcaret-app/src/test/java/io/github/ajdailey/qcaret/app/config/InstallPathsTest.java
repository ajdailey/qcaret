package io.github.ajdailey.qcaret.app.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InstallPathsTest {
  @TempDir Path temp;

  private Path install;
  private Path file;

  @BeforeEach
  void layout() throws IOException {
    install = Files.createDirectories(temp.resolve("install"));
    file =
        Files.writeString(Files.createDirectories(install.resolve("docs")).resolve("a.txt"), "a");
  }

  private static InstallPaths paths(SettingsMode mode, Path directory, Path home) {
    return new InstallPaths(new SettingsLocation(mode, directory, home));
  }

  @Test
  void aRelativeValueResolvesAgainstTheInstallationNotTheWorkingDirectory() throws IOException {
    String expected = install.toRealPath().resolve("docs").resolve("a.txt").toString();
    for (SettingsMode mode : SettingsMode.values()) {
      InstallPaths paths = paths(mode, temp.resolve("settings"), install);
      assertEquals(expected, paths.read("docs/a.txt"), mode.name());
      assertEquals(install.toRealPath().toString(), paths.read("."), mode.name());
    }
    assertNotEquals(Path.of("docs", "a.txt").toAbsolutePath().toString(), expected);
  }

  @Test
  void withoutAnInstallationTheSettingsDirectoryIsTheBase() throws IOException {
    Path settings = Files.createDirectories(temp.resolve("settings"));
    InstallPaths paths = paths(SettingsMode.EXPLICIT, settings, null);
    assertEquals(settings.toRealPath().resolve("x").toString(), paths.read("x"));
    Path inside = Files.writeString(settings.resolve("y.txt"), "y");
    assertEquals("y.txt", paths.write(inside.toString()));
  }

  @Test
  void absoluteAndBlankValuesReadUnchanged() {
    InstallPaths paths = paths(SettingsMode.PORTABLE, install, install);
    assertEquals(file.toString(), paths.read(file.toString()));
    assertEquals("", paths.read(""));
    assertEquals(" ", paths.read(" "));
  }

  @Test
  void portableAndExplicitModeWriteAPathUnderTheInstallationRelative() throws IOException {
    Path settings = Files.createDirectories(install.resolve("bin"));
    InstallPaths[] relative = {
      paths(SettingsMode.PORTABLE, install, install),
      paths(SettingsMode.PORTABLE, settings, install),
      paths(SettingsMode.EXPLICIT, temp.resolve("elsewhere"), install)
    };
    for (InstallPaths paths : relative) {
      assertEquals("docs/a.txt", paths.write(file.toString()));
      assertEquals("docs", paths.write(install.resolve("docs").toString()));
      assertEquals(".", paths.write(install.toString()));
      assertEquals(
          "docs/gone/b.txt",
          paths.write(install.resolve("docs").resolve("gone").resolve("b.txt").toString()));
    }
  }

  @Test
  void perUserModeWritesEveryPathAsGiven() {
    InstallPaths paths = paths(SettingsMode.PER_USER, temp.resolve("settings"), install);
    assertEquals(file.toString(), paths.write(file.toString()));
    assertEquals(install.toString(), paths.write(install.toString()));
  }

  @Test
  void aPathOutsideTheInstallationStaysAbsolute() throws IOException {
    InstallPaths paths = paths(SettingsMode.PORTABLE, install, install);
    Path outside = Files.writeString(temp.resolve("outside.txt"), "o");
    assertEquals(outside.toString(), paths.write(outside.toString()));
    String escape = install.resolve("..").resolve("outside.txt").toString();
    assertEquals(escape, paths.write(escape));
    Path sibling = Files.createDirectories(temp.resolve("install2"));
    assertEquals(sibling.toString(), paths.write(sibling.toString()));
  }

  @Test
  void aLinkIntoTheInstallationWritesRelative() throws IOException {
    Path link = temp.resolve("link");
    try {
      Files.createSymbolicLink(link, install);
    } catch (IOException | UnsupportedOperationException e) {
      Assumptions.assumeTrue(false, "symbolic links unavailable: " + e);
    }
    InstallPaths paths = paths(SettingsMode.PORTABLE, install, install);
    assertEquals("docs/a.txt", paths.write(link.resolve("docs").resolve("a.txt").toString()));
    InstallPaths throughLink = paths(SettingsMode.PORTABLE, link, link);
    assertEquals("docs/a.txt", throughLink.write(file.toString()));
  }
}
