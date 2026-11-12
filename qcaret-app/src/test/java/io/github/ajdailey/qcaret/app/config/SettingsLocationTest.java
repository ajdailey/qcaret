package io.github.ajdailey.qcaret.app.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SettingsLocationTest {
  @TempDir Path temp;

  private SettingsLocation resolve(Path explicit, Path home, String os, Map<String, String> env) {
    return SettingsLocation.resolve(
        explicit, home == null ? null : home.toString(), os, env, temp.toString());
  }

  @Test
  void explicitWinsOverPortable() throws IOException {
    Path home = Files.createDirectory(temp.resolve("home"));
    Files.createFile(home.resolve("qcaret.conf"));
    Path explicit = temp.resolve("explicit");
    assertEquals(
        new SettingsLocation(SettingsMode.EXPLICIT, explicit),
        resolve(explicit, home, "Linux", Map.of()));
    assertEquals(
        new SettingsLocation(SettingsMode.PORTABLE, home), resolve(null, home, "Linux", Map.of()));
  }

  @Test
  void withoutTheMarkerTheDirectoryIsPerUser() throws IOException {
    Path home = Files.createDirectory(temp.resolve("home"));
    Files.createDirectory(home.resolve("qcaret.conf"));
    assertEquals(
        new SettingsLocation(SettingsMode.PER_USER, temp.resolve(".config").resolve("qcaret")),
        resolve(null, home, "Linux", Map.of()));
  }

  @Test
  void perUserDirectoriesFollowTheOs() {
    Path xdg = temp.resolve("xdg");
    assertEquals(
        xdg.resolve("qcaret"),
        resolve(null, null, "Linux", Map.of("XDG_CONFIG_HOME", xdg.toString())).directory());
    assertEquals(
        temp.resolve(".config").resolve("qcaret"),
        resolve(null, null, "Linux", Map.of("XDG_CONFIG_HOME", "relative")).directory());
    Path appData = temp.resolve("Roaming");
    assertEquals(
        appData.resolve("QCaret"),
        resolve(null, null, "Windows 11", Map.of("APPDATA", appData.toString())).directory());
    assertEquals(
        temp.resolve("AppData").resolve("Roaming").resolve("QCaret"),
        resolve(null, null, "Windows 11", Map.of()).directory());
    assertEquals(
        temp.resolve("Library").resolve("Application Support").resolve("QCaret"),
        resolve(null, null, "Mac OS X", Map.of()).directory());
  }

  @Test
  void onlyExplicitAndPortableModeOnLinuxAreConfined() {
    String[] systems = {"Linux", "Windows 11", "Mac OS X"};
    for (SettingsMode mode : SettingsMode.values()) {
      SettingsLocation location = new SettingsLocation(mode, temp);
      for (String os : systems) {
        assertEquals(
            mode != SettingsMode.PER_USER && os.equals("Linux"),
            location.confined(os),
            mode + " " + os);
      }
    }
  }
}
