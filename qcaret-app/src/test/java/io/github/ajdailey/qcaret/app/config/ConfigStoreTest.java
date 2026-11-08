package io.github.ajdailey.qcaret.app.config;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.command.KeyStroke;
import io.github.ajdailey.qcaret.app.command.ShortcutOverrides;
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
import java.util.Map;
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
  void theThemeFileAndFontFamilyRoundTrip() {
    ConfigStore store = store(temp);
    Preferences loaded = store.load();
    Preferences next = loaded.withAppearance(new Appearance("harbor", "Mock Mono"));
    assertTrue(store.save(loaded, next));
    assertEquals(new Appearance("harbor", "Mock Mono"), store(temp).load().appearance());
  }

  @Test
  void anInvalidThemeFileOrFontFamilyFallsBackToEmpty() throws Exception {
    Files.createDirectories(temp);
    Files.writeString(
        temp.resolve(SettingsLocation.MARKER),
        "ui.themeFile=../up\neditor.fontFamily=" + "x".repeat(129) + "\n",
        StandardCharsets.ISO_8859_1);
    assertEquals(Appearance.DEFAULTS, store(temp).load().appearance());
  }

  @Test
  void aMissingFileLoadsDefaults() {
    assertEquals(Preferences.DEFAULTS, store(temp).load());
    assertEquals(ThemeMode.LIGHT, Preferences.DEFAULTS.theme());
  }

  @Test
  void savesVersionAndThemeAtomicallyAndLoadsThemBack() throws IOException {
    ConfigStore store = store(temp);
    assertTrue(store.save(Preferences.DEFAULTS, DARK));
    String text = Files.readString(conf(), StandardCharsets.UTF_8);
    for (String line : List.of("ui.theme=dark", "version=1", "window.maximized=false")) {
      assertTrue(text.contains(line + "\n"), line);
    }
    assertEquals(List.of("qcaret.conf"), names());
    assertEquals(DARK, store(temp).load());
    assertTrue(store.save(DARK, Preferences.DEFAULTS.withTheme(ThemeMode.SYSTEM)));
    assertEquals(ThemeMode.SYSTEM, store(temp).load().theme());
  }

  @Test
  void windowStateRoundTrips() throws IOException {
    Preferences saved = DARK.withWindow(new WindowState(-20, 40, 900, 600, true));
    ConfigStore store = store(temp);
    assertTrue(store.save(Preferences.DEFAULTS, saved));
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
  void theCompletionTriggerRoundTripsAndAnOutOfRangeValueFallsBack() throws IOException {
    Preferences saved = DARK.withOptions(Options.DEFAULTS.withCompletionChars(3));
    assertTrue(store(temp).save(Preferences.DEFAULTS, saved));
    assertTrue(
        Files.readString(conf(), StandardCharsets.ISO_8859_1)
            .contains("editor.completionChars=3\n"));
    assertEquals(saved, store(temp).load());
    Files.writeString(conf(), "editor.completionChars=21\n");
    assertEquals(0, store(temp).load().options().completionChars());
  }

  @Test
  void anUnknownPlacementWritesNoCoordinates() throws IOException {
    assertTrue(store(temp).save(Preferences.DEFAULTS, Preferences.DEFAULTS));
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
    assertTrue(store.save(Preferences.DEFAULTS, DARK));
    assertFalse(Files.exists(invalid()));
  }

  @Test
  void aBadValueFallsBackWithoutMarkingTheFileMalformed() throws IOException {
    Files.writeString(conf(), "version=1\nui.theme=purple\nfuture.key=x\n");
    ConfigStore store = store(temp);
    assertEquals(Preferences.DEFAULTS, store.load());
    assertTrue(store.save(Preferences.DEFAULTS, DARK));
    assertFalse(Files.exists(invalid()));
  }

  @Test
  void aMalformedFileIsKeptAsInvalidBeforeTheFirstSave() throws IOException {
    byte[] bad = "ui.theme=dark\nbroken=\\u12\n".getBytes(StandardCharsets.ISO_8859_1);
    Files.write(conf(), bad);
    ConfigStore store = store(temp);
    assertEquals(Preferences.DEFAULTS, store.load());
    assertFalse(Files.exists(invalid()));
    assertTrue(store.save(Preferences.DEFAULTS, DARK));
    assertArrayEquals(bad, Files.readAllBytes(invalid()));
    assertEquals(DARK, store(temp).load());
    Files.delete(invalid());
    assertTrue(store.save(DARK, Preferences.DEFAULTS));
    assertFalse(Files.exists(invalid()));
  }

  @Test
  void anotherVersionIsMalformed() throws IOException {
    Files.writeString(conf(), "version=2\nui.theme=dark\n");
    ConfigStore store = store(temp);
    assertEquals(Preferences.DEFAULTS, store.load());
    assertTrue(store.save(Preferences.DEFAULTS, Preferences.DEFAULTS));
    assertTrue(Files.exists(invalid()));
  }

  @Test
  void aFileOverOneMebibyteIsMalformed() throws IOException {
    StringBuilder text = new StringBuilder("version=1\nui.theme=dark\n#");
    text.append("x".repeat(ConfigStore.MAX_BYTES));
    Files.writeString(conf(), text.toString());
    ConfigStore store = store(temp);
    assertEquals(Preferences.DEFAULTS, store.load());
    assertTrue(store.save(Preferences.DEFAULTS, DARK));
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
      assertFalse(store.save(DARK, Preferences.DEFAULTS));
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
    assertTrue(store.save(Preferences.DEFAULTS, DARK));
    assertTrue(Files.isRegularFile(dir.resolve("qcaret.conf")));
    if (temp.getFileSystem().supportedFileAttributeViews().contains("posix")) {
      assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(dir)));
    }
  }

  @Test
  void aSaveKeepsTheKeysAnotherStoreChanged() throws IOException {
    Preferences first = Preferences.DEFAULTS.withWindow(new WindowState(1, 2, 300, 400, false));
    assertTrue(store(temp).save(Preferences.DEFAULTS, first));
    ConfigStore a = store(temp);
    ConfigStore b = store(temp);
    Preferences loadedA = a.load();
    Preferences loadedB = b.load();
    Preferences placed = loadedB.withWindow(new WindowState(5, 6, 700, 800, true));
    assertTrue(b.save(loadedB, placed));
    assertTrue(a.save(loadedA, loadedA.withTheme(ThemeMode.DARK)));
    Preferences merged = store(temp).load();
    assertEquals(ThemeMode.DARK, merged.theme());
    assertEquals(placed.window(), merged.window());
  }

  @Test
  void aSaveWithoutChangesLeavesTheFileAlone() throws IOException {
    Files.writeString(conf(), "ui.theme=dark\n");
    ConfigStore store = store(temp);
    Preferences loaded = store.load();
    assertTrue(store.save(loaded, loaded));
    assertEquals("ui.theme=dark\n", Files.readString(conf()));
  }

  @Test
  void aSaveWritesOnlyTheChangedKeysAndKeepsUnknownOnes() throws IOException {
    Files.writeString(conf(), "version=1\nfuture.key=kept\nfile.pollSeconds=abc\n");
    ConfigStore store = store(temp);
    Preferences loaded = store.load();
    assertTrue(store.save(loaded, loaded.withTheme(ThemeMode.DARK)));
    assertEquals(
        List.of("file.pollSeconds=abc", "future.key=kept", "ui.theme=dark", "version=1"),
        Files.readAllLines(conf()));
  }

  @Test
  void aRemovedOptionalKeyLeavesTheFile() throws IOException {
    Preferences placed = DARK.withWindow(new WindowState(1, 2, 300, 400, false));
    ConfigStore store = store(temp);
    assertTrue(store.save(Preferences.DEFAULTS, placed));
    assertTrue(store.save(placed, DARK));
    assertFalse(Files.readString(conf()).contains("window.x"));
  }

  @Test
  void aFileMalformedSinceTheLoadIsKeptBeforeTheSaveReplacesIt() throws IOException {
    ConfigStore store = store(temp);
    Preferences loaded = store.load();
    Files.writeString(conf(), "version=9\n");
    assertTrue(store.save(loaded, DARK));
    assertEquals("version=9\n", Files.readString(invalid()));
    assertEquals(DARK, store(temp).load());
  }

  @Test
  void aSaveOverTheLimitIsRefusedAndTheFileStays() throws IOException {
    String big = "future.key=" + "x".repeat(ConfigStore.MAX_BYTES - 100) + "\n";
    Files.writeString(conf(), big);
    ConfigStore store = store(temp);
    Preferences loaded = store.load();
    UiState state = loaded.state();
    for (int i = 0; i < UiState.MAX_HISTORY; i++) {
      state = state.withHistory(UiState.remember(state.texts(), "text " + i), List.of(), List.of());
    }
    assertFalse(store.save(loaded, loaded.withState(state)));
    assertEquals(big, Files.readString(conf()));
  }

  @Test
  void fullHistoriesOfEscapedTextFitHalfTheLimit() throws IOException {
    List<String> full = new ArrayList<String>();
    for (int i = 0; i < UiState.MAX_HISTORY; i++) {
      full.add(i + "\u00e9".repeat(UiState.MAX_ENTRY_CHARS - 2));
    }
    Preferences saved =
        Preferences.DEFAULTS.withState(
            UiState.DEFAULTS.withHistory(full, full, full).withReplacements(full));
    assertTrue(store(temp).save(Preferences.DEFAULTS, saved));
    assertTrue(Files.size(conf()) < ConfigStore.MAX_BYTES / 2, "size " + Files.size(conf()));
    assertEquals(saved, store(temp).load());
  }

  @Test
  void aTabWidthOutOfRangeFallsBackToTheDefault() throws IOException {
    Files.createDirectories(temp);
    Files.writeString(conf(), "editor.tabWidth=17\neditor.insertSpaces=true\n");
    Options loaded = store(temp).load().options();
    assertEquals(Options.DEFAULTS.tabWidth(), loaded.tabWidth());
    assertTrue(loaded.insertSpaces());
  }

  @Test
  void aBadMarginOrDatePatternFallsBackToTheDefault() throws IOException {
    Files.createDirectories(temp);
    Files.writeString(
        conf(),
        "editor.rightMargin=0\neditor.dateFormat=yyyy {\nui.rightMargin=true\n"
            + "ui.lastDateFormat=HH:mm\n");
    Preferences loaded = store(temp).load();
    assertEquals(Options.DEFAULTS.rightMargin(), loaded.options().rightMargin());
    assertEquals(Options.DEFAULTS.dateFormat(), loaded.options().dateFormat());
    assertTrue(loaded.state().rightMargin());
    assertEquals("HH:mm", loaded.state().lastDateFormat());
  }

  @Test
  void everySettingRoundTrips() throws IOException {
    Options options =
        new Options(
            3,
            false,
            2000,
            14,
            30,
            false,
            9,
            50_000,
            ".git;dist",
            45,
            12,
            true,
            6,
            true,
            100,
            "dd.MM.yyyy",
            true,
            true);
    UiState state =
        new UiState(
                new SearchToggles(true, true, true, true),
                List.of("needle", "two\nlines"),
                List.of("/a"),
                List.of("*.java"),
                List.of("swapped", " padded "),
                List.of("java", "xml"),
                Map.of(),
                false,
                true,
                "HH:mm",
                true,
                true,
                List.of("/mock/favorite", "/mock/other"))
            .withDialog("preferences", new UiState.Size(640, 480));
    ShortcutOverrides shortcuts =
        ShortcutOverrides.NONE
            .with(
                CommandId.COMPARE_DOCUMENTS,
                List.of(new KeyStroke(KeyStroke.PRIMARY | KeyStroke.ALT, 'k')))
            .with(CommandId.FIND, List.of());
    Preferences saved = DARK.withOptions(options).withState(state).withShortcuts(shortcuts);
    assertTrue(store(temp).save(Preferences.DEFAULTS, saved));
    assertEquals(saved, store(temp).load());
    assertTrue(
        Files.readAllLines(conf(), StandardCharsets.ISO_8859_1)
            .containsAll(
                List.of(
                    "editor.fontSize=14",
                    "shortcut.COMPARE_DOCUMENTS=Mod+Alt+K",
                    "shortcut.FIND=",
                    "search.history.replace.0=swapped",
                    "syntax.recent=java;xml",
                    "dialog.preferences.width=640")));
  }

  @Test
  void invalidValuesFallBackOneByOne() throws IOException {
    Files.writeString(
        conf(),
        "editor.autoClose=perhaps\n"
            + "editor.maxHighlightLineChars=999\n"
            + "editor.fontSize=3\n"
            + "file.pollSeconds=0\n"
            + "file.autoReloadClean=1\n"
            + "replace.backupDays=366\n"
            + "search.maxLineChars=nine\n"
            + "workspace.excludedDirectories=a/b\n"
            + "search.regex=maybe\n"
            + "dialog.search.width=0\n"
            + "dialog.search.height=10\n"
            + "dialog.bad-name.width=10\n"
            + "dialog.bad-name.height=10\n"
            + "search.history.text.0=first\n"
            + "search.history.text.2=after a gap\n"
            + "shortcut.FIND=Mod+Nothing\n"
            + "shortcut.COPY=k\n"
            + "shortcut.NOT_A_COMMAND=Mod+K\n");
    Preferences loaded = store(temp).load();
    assertEquals(Options.DEFAULTS, loaded.options());
    assertEquals(SearchToggles.DEFAULTS, loaded.state().search());
    assertTrue(loaded.state().dialogs().isEmpty());
    assertEquals(List.of("first"), loaded.state().texts());
    assertEquals(ShortcutOverrides.NONE, loaded.shortcuts());
  }

  @Test
  void aMergeAppliesTheirChangesOverMine() {
    Preferences base = Preferences.DEFAULTS;
    Preferences theirs = base.withOptions(Options.DEFAULTS.withCompletionChars(4));
    Preferences mine = base.withTheme(ThemeMode.DARK);
    Preferences merged = ConfigStore.merge(base, theirs, mine);
    assertEquals(ThemeMode.DARK, merged.theme());
    assertEquals(4, merged.options().completionChars());
  }

  @Test
  void aMergeKeepsMyNewerValueOfAKeyBothChanged() {
    Preferences base = Preferences.DEFAULTS;
    Preferences theirs = base.withOptions(Options.DEFAULTS.withCompletionChars(4));
    Preferences mine = base.withOptions(Options.DEFAULTS.withCompletionChars(7));
    assertEquals(7, ConfigStore.merge(base, theirs, mine).options().completionChars());
  }
}
