package io.github.ajdailey.qcaret.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.command.KeyStroke;
import io.github.ajdailey.qcaret.app.command.ShortcutOverrides;
import io.github.ajdailey.qcaret.app.config.ConfigStore;
import io.github.ajdailey.qcaret.app.config.Options;
import io.github.ajdailey.qcaret.app.config.Preferences;
import io.github.ajdailey.qcaret.app.config.SettingsLocation;
import io.github.ajdailey.qcaret.app.config.SettingsMode;
import io.github.ajdailey.qcaret.app.config.UiState;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.theme.EditorSchemes;
import io.github.ajdailey.qcaret.app.theme.Palette;
import io.github.ajdailey.qcaret.app.theme.Rgb;
import io.github.ajdailey.qcaret.app.theme.ThemeColors;
import io.github.ajdailey.qcaret.app.theme.ThemeMode;
import io.github.ajdailey.qcaret.app.view.AppearanceChoices;
import io.github.ajdailey.qcaret.app.view.DialogSize;
import io.github.ajdailey.qcaret.app.view.PreferencesForm;
import io.github.ajdailey.qcaret.app.view.PreferencesInput;
import io.github.ajdailey.qcaret.app.view.SearchForm;
import io.github.ajdailey.qcaret.app.view.SearchScope;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tools > Preferences and each setting's effect on the running window. */
class PreferencesControllerTest {
  @TempDir Path temp;

  private Harness harness;

  @BeforeEach
  void create() throws IOException {
    Files.createDirectory(temp.resolve("settings"));
    harness = new Harness(temp);
  }

  @AfterEach
  void close() throws InterruptedException {
    harness.close();
  }

  private PreferencesInput input() throws InterruptedException {
    harness.view.preferences = null;
    harness.application.execute(CommandId.PREFERENCES);
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return harness.view.preferences != null;
          }
        },
        "dialog");
    return harness.view.preferences;
  }

  private ConfigStore store() {
    return new ConfigStore(new SettingsLocation(SettingsMode.EXPLICIT, temp.resolve("settings")));
  }

  private Preferences file(final BooleanSupplier reached) throws InterruptedException {
    harness.await(reached, "saved");
    return store().load();
  }

  private BooleanSupplier fileHas(final String key) {
    return new BooleanSupplier() {
      @Override
      public boolean getAsBoolean() {
        try {
          Path conf = temp.resolve("settings").resolve("qcaret.conf");
          return Files.exists(conf) && Files.readString(conf).contains(key);
        } catch (IOException e) {
          return false;
        }
      }
    };
  }

  private static PreferencesForm with(PreferencesForm f, String field, String value) {
    return new PreferencesForm(
        field.equals("completion") ? value : f.completionChars(),
        f.autoClose(),
        field.equals("highlight") ? value : f.maxHighlightLineChars(),
        f.tabWidth(),
        f.insertSpaces(),
        field.equals("poll") ? value : f.pollSeconds(),
        f.autoReloadClean(),
        field.equals("recoverySeconds") ? value : f.recoveryIntervalSeconds(),
        field.equals("recoveryDays") ? value : f.recoveryKeepDays(),
        f.sessionSnapshot(),
        field.equals("days") ? value : f.backupDays(),
        field.equals("lines") ? value : f.searchMaxLineChars(),
        field.equals("excluded") ? value : f.excludedDirectories(),
        f.theme(),
        field.equals("font") ? value : f.fontSize(),
        f.shortcuts(),
        field.equals("themeFile") ? value : f.themeFile(),
        field.equals("fontFamily") ? value : f.fontFamily(),
        field.equals("rightMargin") ? value : f.rightMargin(),
        field.equals("dateFormat") ? value : f.dateFormat(),
        f.editorConfig(),
        f.modelines(),
        field.equals("editorColors") ? value : f.editorColors(),
        false,
        false);
  }

  @Test
  void theTabWidthDefaultIsCheckedAndApplied() throws Exception {
    PreferencesInput input = input();
    PreferencesForm f = input.form();
    assertEquals("4", f.tabWidth());
    PreferencesForm.Problem refused =
        input.apply(
            new PreferencesForm(
                f.completionChars(),
                f.autoClose(),
                f.maxHighlightLineChars(),
                "17",
                f.insertSpaces(),
                f.pollSeconds(),
                f.autoReloadClean(),
                f.recoveryIntervalSeconds(),
                f.recoveryKeepDays(),
                f.sessionSnapshot(),
                f.backupDays(),
                f.searchMaxLineChars(),
                f.excludedDirectories(),
                f.theme(),
                f.fontSize(),
                f.shortcuts(),
                f.themeFile(),
                f.fontFamily(),
                f.rightMargin(),
                f.dateFormat(),
                f.editorConfig(),
                f.modelines(),
                f.editorColors(),
                false,
                false));
    assertEquals(PreferencesForm.Group.EDITOR, refused.group());
    assertNull(
        input.apply(
            new PreferencesForm(
                f.completionChars(),
                f.autoClose(),
                f.maxHighlightLineChars(),
                "2",
                true,
                f.pollSeconds(),
                f.autoReloadClean(),
                f.recoveryIntervalSeconds(),
                f.recoveryKeepDays(),
                f.sessionSnapshot(),
                f.backupDays(),
                f.searchMaxLineChars(),
                f.excludedDirectories(),
                f.theme(),
                f.fontSize(),
                f.shortcuts(),
                f.themeFile(),
                f.fontFamily(),
                f.rightMargin(),
                f.dateFormat(),
                f.editorConfig(),
                f.modelines(),
                f.editorColors(),
                false,
                false)));
    assertEquals(2, harness.application.preferences().options().tabWidth());
    assertTrue(harness.application.preferences().options().insertSpaces());
  }

  @Test
  void recoverySettingsAreCheckedAndApplied() throws Exception {
    PreferencesInput input = input();
    PreferencesForm f = input.form();
    assertEquals(PreferencesForm.Group.FILES, input.apply(with(f, "recoverySeconds", "4")).group());
    assertEquals(PreferencesForm.Group.FILES, input.apply(with(f, "recoveryDays", "366")).group());
    assertNull(input.apply(with(f, "recoverySeconds", "45")));
    assertEquals(45, harness.application.preferences().options().recoveryIntervalSeconds());
  }

  @Test
  void theDialogOpensOnceTheFileIsReadAndShowsAnotherProcessesValues() throws Exception {
    ConfigStore other = store();
    Preferences loaded = other.load();
    assertTrue(other.save(loaded, loaded.withOptions(Options.DEFAULTS.withCompletionChars(6))));
    PreferencesInput input = input();
    assertEquals("6", input.form().completionChars());
    assertEquals(6, harness.application.preferences().options().completionChars());
  }

  @Test
  void eachInvalidFieldIsNamedAndNothingChanges() throws Exception {
    PreferencesInput input = input();
    PreferencesForm good = input.form();
    Preferences before = harness.application.preferences();
    Object[][] cases = {
      {"completion", "21", PreferencesForm.Group.EDITOR, "Word chars"},
      {"highlight", "x", PreferencesForm.Group.EDITOR, "Longest line with syntax colors"},
      {"poll", "0", PreferencesForm.Group.FILES, "Seconds between"},
      {"days", "366", PreferencesForm.Group.SEARCH, "Days a replace"},
      {"lines", "", PreferencesForm.Group.SEARCH, "Longest line searched"},
      {"excluded", "a/b", PreferencesForm.Group.SEARCH, "Excluded directories"},
      {"font", "3", PreferencesForm.Group.APPEARANCE, "Editor font size"}
    };
    for (Object[] c : cases) {
      PreferencesForm.Problem problem = input.apply(with(good, (String) c[0], (String) c[1]));
      assertNotNull(problem, (String) c[0]);
      assertEquals(c[2], problem.group());
      assertTrue(problem.message().startsWith((String) c[3]), problem.message());
    }
    assertEquals(before, harness.application.preferences());
  }

  @Test
  void defaultsFillTheFormWithoutChangingAnything() throws Exception {
    PreferencesInput input = input();
    assertNull(input.apply(with(input.form(), "poll", "9")));
    Preferences applied = harness.application.preferences();
    PreferencesForm defaults = input.defaults();
    assertEquals("5", defaults.pollSeconds());
    assertEquals(".git;.svn;.hg;target;node_modules;build", defaults.excludedDirectories());
    assertEquals("", defaults.fontSize());
    assertEquals(applied, harness.application.preferences());
    assertNull(input.apply(defaults));
    assertEquals(Options.DEFAULTS, harness.application.preferences().options());
  }

  @Test
  void applySavesTheChangedKeyOnAWorker() throws Exception {
    PreferencesInput input = input();
    assertNull(input.apply(with(input.form(), "days", "12")));
    Preferences saved = file(fileHas("replace.backupDays=12"));
    assertEquals(12, saved.options().backupDays());
  }

  @Test
  void theThemeAppliesAtOnce() throws Exception {
    PreferencesInput input = input();
    PreferencesForm f = input.form();
    assertNull(
        input.apply(
            new PreferencesForm(
                f.completionChars(),
                f.autoClose(),
                f.maxHighlightLineChars(),
                f.tabWidth(),
                f.insertSpaces(),
                f.pollSeconds(),
                f.autoReloadClean(),
                f.recoveryIntervalSeconds(),
                f.recoveryKeepDays(),
                f.sessionSnapshot(),
                f.backupDays(),
                f.searchMaxLineChars(),
                f.excludedDirectories(),
                ThemeMode.DARK,
                f.fontSize(),
                f.shortcuts(),
                f.themeFile(),
                f.fontFamily(),
                f.rightMargin(),
                f.dateFormat(),
                f.editorConfig(),
                f.modelines(),
                f.editorColors(),
                false,
                false)));
    assertEquals(Palette.DARK, harness.application.theme().palette());
  }

  @Test
  void editorColorsApplyAtOnceWithoutANoticeAndKeepTheTheme() throws Exception {
    PreferencesInput input = input();
    assertEquals("follow", input.form().editorColors());
    assertNull(input.apply(with(input.form(), "editorColors", "dark")));
    assertEquals(Palette.DARK, harness.application.theme().palette());
    assertEquals(ThemeMode.LIGHT, harness.application.theme().mode());
    assertEquals("dark", file(fileHas("ui.editorColors=dark")).appearance().editorColors());
    assertEquals(List.of(), harness.view.notices);
    assertEquals(List.of(), harness.view.infos);
  }

  @Test
  void theFontSizeAppliesAndZoomStepsIt() throws Exception {
    PreferencesInput input = input();
    assertNull(input.apply(with(input.form(), "font", "14")));
    assertEquals(14, harness.view.fontSize);
    harness.application.execute(CommandId.ZOOM_IN);
    assertEquals(15, harness.view.fontSize);
    assertEquals(15, file(fileHas("editor.fontSize=15")).options().fontSize());
    harness.application.execute(CommandId.ZOOM_RESET);
    assertEquals(0, harness.view.fontSize);
    harness.application.execute(CommandId.ZOOM_IN);
    assertEquals(11, harness.view.fontSize);
  }

  @Test
  void aThemeFileAppliesAtOnceAndAnApplyReadsItsEdits() throws Exception {
    Path themes = Files.createDirectories(temp.resolve("settings").resolve(ThemeColors.DIRECTORY));
    final Path file = themes.resolve("harbor.theme");
    Files.writeString(file, "light.background = #102030\n");
    final PreferencesInput input = input();
    assertNull(input.apply(with(input.form(), "themeFile", "harbor")));
    harness.await(background(0x102030), "theme file applied");
    assertEquals("harbor", file(fileHas("ui.editorColors=harbor")).appearance().themeFile());
    Files.writeString(file, "light.background = #203040\n");
    assertNull(input.apply(input.form()));
    harness.await(background(0x203040), "edit applied");
    assertNull(input.apply(with(with(input.form(), "themeFile", ""), "editorColors", "paper")));
    assertEquals(
        EditorSchemes.palette(EditorSchemes.PAPER, ThemeMode.LIGHT, false),
        harness.application.theme().palette());
    assertEquals("paper", file(fileHas("ui.editorColors=paper")).appearance().editorColors());
    assertNull(input.apply(with(input.form(), "editorColors", "follow")));
    harness.await(background(0xffffff), "built-in colors back");
  }

  private BooleanSupplier background(final int rgb) {
    return new BooleanSupplier() {
      @Override
      public boolean getAsBoolean() {
        return harness.application.theme().palette().background().equals(Rgb.of(rgb));
      }
    };
  }

  @Test
  void theChoicesListTheThemeFiles() throws Exception {
    Path themes = Files.createDirectories(temp.resolve("settings").resolve(ThemeColors.DIRECTORY));
    Files.writeString(themes.resolve("b.theme"), "");
    Files.writeString(themes.resolve("a.theme"), "");
    Files.writeString(themes.resolve("skipped.txt"), "");
    final AtomicReference<AppearanceChoices> seen = new AtomicReference<AppearanceChoices>();
    input()
        .loadChoices(
            new Consumer<AppearanceChoices>() {
              @Override
              public void accept(AppearanceChoices choices) {
                seen.set(choices);
              }
            });
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return seen.get() != null;
          }
        },
        "choices");
    assertEquals(List.of("a", "b"), seen.get().themeFiles());
  }

  @Test
  void theFontFamilyReachesTheViewAndIsSaved() throws Exception {
    PreferencesInput input = input();
    assertNull(input.apply(with(input.form(), "fontFamily", "Mock Mono")));
    assertEquals("Mock Mono", harness.view.fontFamily);
    assertEquals("Mock Mono", harness.application.fontFamily());
    assertEquals(
        "Mock Mono", file(fileHas("editor.fontFamily=Mock Mono")).appearance().fontFamily());
  }

  @Test
  void keysApplyToTheKeymapTheViewAndTheReference() throws Exception {
    PreferencesInput input = input();
    PreferencesForm f = input.form();
    KeyStroke key = new KeyStroke(KeyStroke.PRIMARY | KeyStroke.ALT, 'k');
    ShortcutOverrides overrides =
        ShortcutOverrides.NONE.with(CommandId.COMPARE_DOCUMENTS, List.of(key));
    assertEquals(key, input.keymap(overrides).accelerator(CommandId.COMPARE_DOCUMENTS));
    assertEquals(harness.application.keymap().bindings(), input.keymap(f.shortcuts()).bindings());
    assertNull(
        input.apply(
            new PreferencesForm(
                f.completionChars(),
                f.autoClose(),
                f.maxHighlightLineChars(),
                f.tabWidth(),
                f.insertSpaces(),
                f.pollSeconds(),
                f.autoReloadClean(),
                f.recoveryIntervalSeconds(),
                f.recoveryKeepDays(),
                f.sessionSnapshot(),
                f.backupDays(),
                f.searchMaxLineChars(),
                f.excludedDirectories(),
                f.theme(),
                f.fontSize(),
                overrides,
                f.themeFile(),
                f.fontFamily(),
                f.rightMargin(),
                f.dateFormat(),
                f.editorConfig(),
                f.modelines(),
                f.editorColors(),
                false,
                false)));
    assertEquals(key, harness.view.keymap.accelerator(CommandId.COMPARE_DOCUMENTS));
    assertEquals(key, harness.application.keymap().accelerator(CommandId.COMPARE_DOCUMENTS));
    harness.application.execute(CommandId.KEYBOARD_SHORTCUTS);
    assertEquals("Ctrl+Alt+K", harness.view.shortcuts.keysText(CommandId.COMPARE_DOCUMENTS));
  }

  @Test
  void autoCloseFollowsTheSetting() throws Exception {
    Path file = Files.writeString(temp.resolve("a.txt"), "");
    harness.open(file);
    EditorController editor = harness.application.documents().activeEditor();
    editor.type("(");
    assertEquals("()", editor.document().text().toString());
    Preferences current = harness.application.preferences();
    harness.application.settings().set(current.withOptions(off(current.options())));
    editor.type("(");
    assertEquals("(()", editor.document().text().toString());
  }

  private static Options off(Options o) {
    return new Options(
        o.completionChars(),
        false,
        o.maxHighlightLineChars(),
        o.fontSize(),
        o.pollSeconds(),
        o.autoReloadClean(),
        o.backupDays(),
        o.searchMaxLineChars(),
        o.excludedDirectories(),
        o.recoveryIntervalSeconds(),
        o.recoveryKeepDays(),
        o.sessionSnapshot(),
        o.tabWidth(),
        o.insertSpaces(),
        Options.DEFAULTS.rightMargin(),
        Options.DEFAULTS.dateFormat(),
        Options.DEFAULTS.editorConfig(),
        Options.DEFAULTS.modelines(),
        false,
        false);
  }

  @Test
  void searchTogglesAreOneCopySharedByTheBarAndHyperSearch() throws Exception {
    harness.application.find().setOptions(true, false, true);
    assertTrue(harness.application.preferences().state().search().regex());
    assertTrue(harness.application.find().state().regex());
    harness.application.execute(CommandId.HYPERSEARCH);
    SearchForm form = harness.view.searchForm;
    assertTrue(form.regex() && form.caseSensitive());
    assertFalse(form.wholeWord());
    harness
        .application
        .search()
        .run(
            new SearchForm(
                "x",
                false,
                false,
                true,
                SearchScope.OPEN_DOCUMENTS,
                "",
                "*.txt",
                "",
                true,
                "",
                List.of(),
                List.of(),
                List.of()));
    assertTrue(harness.application.find().state().wholeWord());
    assertFalse(harness.application.find().state().regex());
    Preferences saved = file(fileHas("search.history.text.0=x"));
    assertTrue(saved.state().search().hiddenFiles());
    assertEquals(List.of("x"), saved.state().texts());
    assertEquals(List.of("*.txt"), saved.state().masks());
  }

  private void remember(String text) {
    Settings settings = harness.application.settings();
    Preferences current = settings.current();
    settings.set(
        current.withState(
            current
                .state()
                .withHistory(List.of(text), List.of("/mock"), List.of("*.mock"))
                .withReplacements(List.of("swapped"))));
  }

  private void awaitCleared() throws InterruptedException {
    final Settings settings = harness.application.settings();
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return settings.current().state().equals(UiState.DEFAULTS);
          }
        },
        "cleared");
  }

  /** Saves what is queued: a file condition alone wakes no wait on the UI queue. */
  private Preferences flushed() {
    harness.application.settings().flush();
    return store().load();
  }

  @Test
  void clearHistoryEmptiesEveryListAtOnceAndApplyKeepsThemEmpty() throws Exception {
    remember("needle");
    assertEquals(List.of("swapped"), flushed().state().replacements());
    PreferencesInput input = input();
    PreferencesForm form = input.form();
    input.clearHistory();
    awaitCleared();
    assertEquals(UiState.DEFAULTS, flushed().state());
    assertFalse(fileHas("search.history.").getAsBoolean());
    assertNull(input.apply(form));
    assertEquals(UiState.DEFAULTS, harness.application.settings().current().state());
  }

  @Test
  void clearHistoryTakesEntriesAnotherProcessSaved() throws Exception {
    remember("needle");
    flushed();
    PreferencesInput input = input();
    Preferences mine = harness.application.settings().current();
    UiState state = mine.state();
    assertTrue(
        store()
            .save(
                mine,
                mine.withState(
                    state.withHistory(
                        List.of("other", "needle"), state.directories(), state.masks()))));
    input.clearHistory();
    awaitCleared();
    assertEquals(UiState.DEFAULTS, flushed().state());
    assertFalse(fileHas("search.history.").getAsBoolean());
  }

  @Test
  void clearHistoryDropsHyperSearchsLastRun() throws Exception {
    harness
        .application
        .search()
        .run(
            new SearchForm(
                "x",
                false,
                false,
                false,
                SearchScope.OPEN_DOCUMENTS,
                "",
                "*.txt",
                "*.log",
                false,
                "",
                List.of(),
                List.of(),
                List.of()));
    harness.application.execute(CommandId.HYPERSEARCH);
    assertEquals("*.txt", harness.view.searchForm.include());
    input().clearHistory();
    awaitCleared();
    harness.application.execute(CommandId.HYPERSEARCH);
    SearchForm form = harness.view.searchForm;
    assertEquals("", form.text());
    assertEquals("", form.include());
    assertEquals("", form.exclude());
    assertEquals(SearchScope.CURRENT_DOCUMENT, form.scope());
    assertEquals(List.of(), form.textHistory());
  }

  private void awaitNotices(final int count) throws InterruptedException {
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return harness.view.notices.size() == count;
          }
        },
        count + " notices");
  }

  @Test
  void aFailedClearSaysSoEachTimeAndTheFileKeepsTheHistory() throws Exception {
    assumeTrue(temp.getFileSystem().supportedFileAttributeViews().contains("posix"), "no POSIX");
    remember("needle");
    flushed();
    PreferencesInput input = input();
    Path directory = temp.resolve("settings");
    int before = harness.view.notices.size();
    Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("r-x------"));
    try {
      assumeTrue(!Files.isWritable(directory), "running with permission to write anyway");
      input.clearHistory();
      awaitNotices(before + 1);
      assertEquals(UiText.PREFERENCES_HISTORY_NOT_REMOVED.text(), harness.view.notices.get(before));
      assertEquals(UiState.DEFAULTS, harness.application.settings().current().state());
      input.clearHistory();
      awaitNotices(before + 2);
      assertEquals(List.of("needle"), store().load().state().texts());
    } finally {
      Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
    }
  }

  private int unreadableNotices() {
    int count = 0;
    for (String notice : harness.view.notices) {
      if (notice.equals(UiText.PREFERENCES_FILE_UNREADABLE.text())) {
        count++;
      }
    }
    return count;
  }

  @Test
  void aMalformedFileKeepsTheSettingsAndShowsOneNoticePerRun() throws Exception {
    Preferences mine = harness.application.settings().current();
    Files.writeString(temp.resolve("settings").resolve("qcaret.conf"), "version=2\n");
    input();
    assertEquals(1, unreadableNotices());
    assertEquals(mine, harness.application.settings().current());
    input();
    assertEquals(1, unreadableNotices());
  }

  @Test
  void aMissingFileShowsNoUnreadableNotice() throws Exception {
    assertFalse(Files.exists(temp.resolve("settings").resolve("qcaret.conf")));
    input();
    input();
    assertEquals(0, unreadableNotices());
  }

  @Test
  void clearingNothingOnAReadOnlyDirectoryShowsNoNotice() throws Exception {
    assumeTrue(temp.getFileSystem().supportedFileAttributeViews().contains("posix"), "no POSIX");
    PreferencesInput input = input();
    Path directory = temp.resolve("settings");
    int before = harness.view.notices.size();
    Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("r-x------"));
    try {
      assumeTrue(!Files.isWritable(directory), "running with permission to write anyway");
      input.clearHistory();
      final boolean[] done = new boolean[1];
      harness
          .application
          .settings()
          .refresh(
              new Runnable() {
                @Override
                public void run() {
                  done[0] = true;
                }
              });
      harness.await(
          new BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
              return done[0];
            }
          },
          "refreshed");
      harness.ui.runPending();
      assertEquals(before, harness.view.notices.size());
    } finally {
      Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
    }
  }

  @Test
  void dialogSizesAreRemembered() throws Exception {
    assertNull(harness.application.dialogSize("search"));
    harness.application.rememberDialogSize("search", new DialogSize(500, 400));
    assertEquals(500, harness.application.dialogSize("search").width());
    assertEquals(
        400, file(fileHas("dialog.search.height=400")).state().dialogs().get("search").height());
  }

  @Test
  void excludedDirectoriesApplyToTheIndexAndToSearches() throws Exception {
    final Path root = Files.createDirectory(temp.resolve("ws")).toRealPath();
    Files.createDirectory(root.resolve("target"));
    Files.createDirectory(root.resolve("keep"));
    Files.writeString(root.resolve("target").resolve("t.txt"), "x");
    Files.writeString(root.resolve("keep").resolve("k.txt"), "x");
    final WorkspaceController workspace = harness.application.workspace();
    workspace.set(root);
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return workspace.index() != null;
          }
        },
        "indexed");
    assertEquals(1, workspace.index().size());
    PreferencesInput input = input();
    assertNull(input.apply(with(input.form(), "excluded", "keep")));
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return workspace.index() != null
                && workspace.index().size() == 1
                && workspace.index().path(0).startsWith("target");
          }
        },
        "indexed again");
    final SearchController search = harness.application.search();
    search.run(SearchControllerTest.form("x", SearchScope.TREE, root.toString()));
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !search.sets().isEmpty() && !search.running();
          }
        },
        "search ends");
    assertEquals(1, search.sets().get(0).files().size());
    assertEquals("t.txt", search.sets().get(0).files().get(0).path().getFileName().toString());
  }
}
