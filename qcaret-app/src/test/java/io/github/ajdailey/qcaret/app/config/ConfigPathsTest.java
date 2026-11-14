package io.github.ajdailey.qcaret.app.config;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.ajdailey.qcaret.app.theme.ThemeMode;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code qcaret.conf}'s path keys through {@link InstallPaths}. */
class ConfigPathsTest {
  @TempDir Path temp;

  private static Properties keys(Path conf) throws IOException {
    Properties p = new Properties();
    try (Reader in = Files.newBufferedReader(conf, StandardCharsets.ISO_8859_1)) {
      p.load(in);
    }
    return p;
  }

  private static void store(Path conf, Properties p) throws IOException {
    try (OutputStream out = Files.newOutputStream(conf)) {
      p.store(out, null);
    }
  }

  /** Marks the file old, so a write shows in its modified time. */
  private static FileTime age(Path conf) throws IOException {
    FileTime old = FileTime.fromMillis(1_000_000_000_000L);
    Files.setLastModifiedTime(conf, old);
    return old;
  }

  /**
   * Writes the keys an earlier version saved: absolute paths under the install for a favorite, a
   * search directory and the default, a favorite outside it, and two other keys.
   */
  private static void upgraded(Path conf, Path install, Path outside) throws IOException {
    Properties p = new Properties();
    p.setProperty("version", "1");
    p.setProperty("ui.theme", "dark");
    p.setProperty("mock.unknown", "kept");
    p.setProperty("workspace.favorite.0", install.resolve("logs").toString());
    p.setProperty("workspace.favorite.1", outside.toString());
    p.setProperty("search.history.directory.0", install.resolve("data").toString());
    p.setProperty("workspace.default", install.resolve("logs").toString());
    store(conf, p);
  }

  private Path install() throws IOException {
    Path install = Files.createDirectories(temp.resolve("install"));
    Files.createDirectories(install.resolve("logs"));
    Files.createDirectories(install.resolve("data"));
    return install;
  }

  /** Saves a favorite, a search directory and the default under the install, plus one outside. */
  private static Preferences withPaths(Path install, Path outside) {
    String logs = install.resolve("logs").toString();
    UiState state =
        Preferences.DEFAULTS
            .state()
            .withFavorites(List.of(logs, outside.toString()))
            .withHistory(List.of(), List.of(install.resolve("data").toString()), List.of());
    return Preferences.DEFAULTS
        .withOptions(Preferences.DEFAULTS.options().withWorkspaceDefault(logs))
        .withState(state);
  }

  @Test
  void aMovedPortableInstallKeepsItsFavoritesHistoryAndDefault() throws IOException {
    Path install = Files.createDirectories(temp.resolve("install"));
    Files.createDirectories(install.resolve("logs"));
    Files.createDirectories(install.resolve("data"));
    Files.createFile(install.resolve(SettingsLocation.MARKER));
    Path outside = Files.createDirectories(temp.resolve("outside"));
    ConfigStore store =
        new ConfigStore(new SettingsLocation(SettingsMode.PORTABLE, install, install));
    assertTrue(store.save(Preferences.DEFAULTS, withPaths(install, outside)));
    Properties written = keys(install.resolve(SettingsLocation.MARKER));
    assertEquals("logs", written.getProperty("workspace.favorite.0"));
    assertEquals(outside.toString(), written.getProperty("workspace.favorite.1"));
    assertEquals("data", written.getProperty("search.history.directory.0"));
    assertEquals("logs", written.getProperty("workspace.default"));

    Path moved = Files.move(install, temp.resolve("moved"));
    Path real = moved.toRealPath();
    Preferences loaded =
        new ConfigStore(new SettingsLocation(SettingsMode.PORTABLE, moved, moved)).load();
    assertEquals(
        List.of(real.resolve("logs").toString(), outside.toString()), loaded.state().favorites());
    assertEquals(List.of(real.resolve("data").toString()), loaded.state().directories());
    assertEquals(real.resolve("logs").toString(), loaded.options().workspaceDefault());
  }

  @Test
  void perUserModeWritesAbsoluteAndResolvesAHandEditedRelativeValueAgainstTheInstall()
      throws IOException {
    Path install = Files.createDirectories(temp.resolve("install"));
    Path settings = Files.createDirectories(temp.resolve("settings"));
    Path outside = Files.createDirectories(temp.resolve("outside"));
    SettingsLocation location = new SettingsLocation(SettingsMode.PER_USER, settings, install);
    assertTrue(new ConfigStore(location).save(Preferences.DEFAULTS, withPaths(install, outside)));
    Properties written = keys(settings.resolve(SettingsLocation.MARKER));
    assertEquals(install.resolve("logs").toString(), written.getProperty("workspace.favorite.0"));
    assertEquals(install.resolve("logs").toString(), written.getProperty("workspace.default"));

    Files.writeString(
        settings.resolve(SettingsLocation.MARKER),
        "workspace.default=logs\nworkspace.favorite.0=data\n",
        StandardCharsets.ISO_8859_1);
    Preferences loaded = new ConfigStore(location).load();
    Path real = install.toRealPath();
    assertEquals(real.resolve("logs").toString(), loaded.options().workspaceDefault());
    assertEquals(List.of(real.resolve("data").toString()), loaded.state().favorites());
  }

  @Test
  void anInvalidDefaultReadsAsNone() throws IOException {
    Files.writeString(
        temp.resolve(SettingsLocation.MARKER),
        "workspace.default=/mock\\u0001folder\n",
        StandardCharsets.ISO_8859_1);
    Preferences loaded = new ConfigStore(new SettingsLocation(SettingsMode.EXPLICIT, temp)).load();
    assertEquals("", loaded.options().workspaceDefault());
  }

  @Test
  void anUpgradedPortableFileIsRewrittenRelativeOnceAndSurvivesAMove() throws IOException {
    Path install = install();
    Path outside = Files.createDirectories(temp.resolve("outside"));
    Path conf = install.resolve(SettingsLocation.MARKER);
    upgraded(conf, install, outside);
    new ConfigStore(new SettingsLocation(SettingsMode.PORTABLE, install, install)).load();
    Properties written = keys(conf);
    assertEquals("logs", written.getProperty("workspace.favorite.0"));
    assertEquals(outside.toString(), written.getProperty("workspace.favorite.1"));
    assertEquals("data", written.getProperty("search.history.directory.0"));
    assertEquals("logs", written.getProperty("workspace.default"));
    assertEquals("dark", written.getProperty("ui.theme"));
    assertEquals("kept", written.getProperty("mock.unknown"));
    assertEquals(7, written.size());

    byte[] once = Files.readAllBytes(conf);
    FileTime old = age(conf);
    new ConfigStore(new SettingsLocation(SettingsMode.PORTABLE, install, install)).load();
    assertEquals(old, Files.getLastModifiedTime(conf));
    assertArrayEquals(once, Files.readAllBytes(conf));

    Path moved = Files.move(install, temp.resolve("moved"));
    Path real = moved.toRealPath();
    Preferences loaded =
        new ConfigStore(new SettingsLocation(SettingsMode.PORTABLE, moved, moved)).load();
    assertEquals(
        List.of(real.resolve("logs").toString(), outside.toString()), loaded.state().favorites());
    assertEquals(List.of(real.resolve("data").toString()), loaded.state().directories());
    assertEquals(real.resolve("logs").toString(), loaded.options().workspaceDefault());
    assertEquals(ThemeMode.DARK, loaded.theme());
  }

  @Test
  void perUserModeLeavesAnUpgradedFileAsItIs() throws IOException {
    Path install = install();
    Path settings = Files.createDirectories(temp.resolve("settings"));
    Path conf = settings.resolve(SettingsLocation.MARKER);
    upgraded(conf, install, Files.createDirectories(temp.resolve("outside")));
    byte[] before = Files.readAllBytes(conf);
    FileTime old = age(conf);
    new ConfigStore(new SettingsLocation(SettingsMode.PER_USER, settings, install)).load();
    assertEquals(old, Files.getLastModifiedTime(conf));
    assertArrayEquals(before, Files.readAllBytes(conf));
  }

  @Test
  void aReadOnlySettingsDirectorySkipsTheRewrite() throws IOException {
    assumeTrue(temp.getFileSystem().supportedFileAttributeViews().contains("posix"), "no POSIX");
    Path install = install();
    Path conf = install.resolve(SettingsLocation.MARKER);
    upgraded(conf, install, Files.createDirectories(temp.resolve("outside")));
    byte[] before = Files.readAllBytes(conf);
    Files.setPosixFilePermissions(install, PosixFilePermissions.fromString("r-x------"));
    try {
      assumeTrue(!Files.isWritable(install), "running with permission to write anyway");
      ConfigStore store =
          new ConfigStore(new SettingsLocation(SettingsMode.PORTABLE, install, install));
      Preferences loaded = store.load();
      assertTrue(store.saveDisabled());
      assertEquals(install.resolve("logs").toString(), loaded.options().workspaceDefault());
      assertArrayEquals(before, Files.readAllBytes(conf));
    } finally {
      Files.setPosixFilePermissions(install, PosixFilePermissions.fromString("rwx------"));
    }
  }
}
