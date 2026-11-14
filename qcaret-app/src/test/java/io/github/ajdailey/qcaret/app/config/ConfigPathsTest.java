package io.github.ajdailey.qcaret.app.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
}
