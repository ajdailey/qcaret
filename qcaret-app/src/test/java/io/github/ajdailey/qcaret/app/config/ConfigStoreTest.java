package io.github.ajdailey.qcaret.app.config;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.ajdailey.qcaret.app.theme.ThemeMode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigStoreTest {
  private static final Preferences DARK = Preferences.DEFAULTS.withTheme(ThemeMode.DARK);

  @TempDir Path temp;

  private ConfigStore store(Path dir) {
    return new ConfigStore(new SettingsLocation(SettingsMode.EXPLICIT, dir));
  }

  private Path conf() {
    return temp.resolve("qcaret.conf");
  }

  private Path invalid() {
    return temp.resolve("qcaret.conf.invalid");
  }

  private List<String> names() throws IOException {
    List<String> names = new ArrayList<String>();
    try (DirectoryStream<Path> entries = Files.newDirectoryStream(temp)) {
      for (Path entry : entries) {
        names.add(entry.getFileName().toString());
      }
    }
    Collections.sort(names);
    return names;
  }

  @Test
  void aMissingFileLoadsDefaults() {
    assertEquals(Preferences.DEFAULTS, store(temp).load());
    assertEquals(ThemeMode.LIGHT, Preferences.DEFAULTS.theme());
  }

  @Test
  void savesVersionAndThemeAtomicallyAndLoadsThemBack() throws IOException {
    ConfigStore store = store(temp);
    assertTrue(store.save(DARK));
    String text = Files.readString(conf(), StandardCharsets.UTF_8);
    for (String line : List.of("ui.theme=dark", "version=1", "window.maximized=false")) {
      assertTrue(text.contains(line + "\n"), line);
    }
    assertEquals(List.of("qcaret.conf"), names());
    assertEquals(DARK, store(temp).load());
    assertTrue(store.save(Preferences.DEFAULTS.withTheme(ThemeMode.SYSTEM)));
    assertEquals(ThemeMode.SYSTEM, store(temp).load().theme());
  }

  @Test
  void windowStateRoundTrips() throws IOException {
    Preferences saved = DARK.withWindow(new WindowState(-20, 40, 900, 600, true));
    ConfigStore store = store(temp);
    assertTrue(store.save(saved));
    assertEquals(saved, store(temp).load());
    String text = Files.readString(conf(), StandardCharsets.ISO_8859_1);
    for (String line :
        List.of(
            "window.x=-20",
            "window.y=40",
            "window.width=900",
            "window.height=600",
            "window.maximized=true")) {
      assertTrue(text.contains(line + "\n"), line);
    }
  }

  @Test
  void anUnknownPlacementWritesNoCoordinates() throws IOException {
    assertTrue(store(temp).save(Preferences.DEFAULTS));
    String text = Files.readString(conf(), StandardCharsets.ISO_8859_1);
    assertFalse(text.contains("window.x"));
    assertFalse(text.contains("window.width"));
    assertEquals(WindowState.UNKNOWN, store(temp).load().window());
  }

  @Test
  void outOfRangeWindowValuesFallBackOneByOne() throws IOException {
    Files.writeString(
        conf(),
        "window.x=-100001\nwindow.y=12\nwindow.width=0\nwindow.height=wide\n"
            + "window.maximized=maybe\n");
    assertEquals(new WindowState(null, 12, null, null, false), store(temp).load().window());
  }

  @Test
  void anEmptyMarkerIsValid() throws IOException {
    Files.createFile(conf());
    ConfigStore store = store(temp);
    assertEquals(Preferences.DEFAULTS, store.load());
    assertTrue(store.save(DARK));
    assertFalse(Files.exists(invalid()));
  }

  @Test
  void aBadValueFallsBackWithoutMarkingTheFileMalformed() throws IOException {
    Files.writeString(conf(), "version=1\nui.theme=purple\nfuture.key=x\n");
    ConfigStore store = store(temp);
    assertEquals(Preferences.DEFAULTS, store.load());
    assertTrue(store.save(DARK));
    assertFalse(Files.exists(invalid()));
  }

  @Test
  void aMalformedFileIsKeptAsInvalidBeforeTheFirstSave() throws IOException {
    byte[] bad = "ui.theme=dark\nbroken=\\u12\n".getBytes(StandardCharsets.ISO_8859_1);
    Files.write(conf(), bad);
    ConfigStore store = store(temp);
    assertEquals(Preferences.DEFAULTS, store.load());
    assertFalse(Files.exists(invalid()));
    assertTrue(store.save(DARK));
    assertArrayEquals(bad, Files.readAllBytes(invalid()));
    assertEquals(DARK, store(temp).load());
    Files.delete(invalid());
    assertTrue(store.save(Preferences.DEFAULTS));
    assertFalse(Files.exists(invalid()));
  }

  @Test
  void anotherVersionIsMalformed() throws IOException {
    Files.writeString(conf(), "version=2\nui.theme=dark\n");
    ConfigStore store = store(temp);
    assertEquals(Preferences.DEFAULTS, store.load());
    assertTrue(store.save(Preferences.DEFAULTS));
    assertTrue(Files.exists(invalid()));
  }

  @Test
  void aFileOverOneMebibyteIsMalformed() throws IOException {
    StringBuilder text = new StringBuilder("version=1\nui.theme=dark\n#");
    text.append("x".repeat(ConfigStore.MAX_BYTES));
    Files.writeString(conf(), text.toString());
    ConfigStore store = store(temp);
    assertEquals(Preferences.DEFAULTS, store.load());
    assertTrue(store.save(DARK));
    assertTrue(Files.size(invalid()) > ConfigStore.MAX_BYTES);
  }

  @Test
  void aReadOnlyDirectoryDisablesSaving() throws IOException {
    assumeTrue(temp.getFileSystem().supportedFileAttributeViews().contains("posix"), "no POSIX");
    Path dir = Files.createDirectory(temp.resolve("read-only"));
    Files.writeString(dir.resolve("qcaret.conf"), "ui.theme=dark\n");
    Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("r-x------"));
    try {
      assumeTrue(!Files.isWritable(dir), "running with permission to write anyway");
      ConfigStore store = store(dir);
      assertEquals(DARK, store.load());
      assertTrue(store.saveDisabled());
      assertFalse(store.save(Preferences.DEFAULTS));
      assertEquals("ui.theme=dark\n", Files.readString(dir.resolve("qcaret.conf")));
    } finally {
      Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
    }
  }

  @Test
  void aPerUserDirectoryIsCreatedOwnerOnlyOnSave() throws IOException {
    Path dir = temp.resolve("config").resolve("qcaret");
    ConfigStore store = new ConfigStore(new SettingsLocation(SettingsMode.PER_USER, dir));
    assertEquals(Preferences.DEFAULTS, store.load());
    assertFalse(Files.exists(dir));
    assertTrue(store.save(DARK));
    assertTrue(Files.isRegularFile(dir.resolve("qcaret.conf")));
    if (temp.getFileSystem().supportedFileAttributeViews().contains("posix")) {
      assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(dir)));
    }
  }
}
