package io.github.ajdailey.qcaret.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.command.KeyStroke;
import io.github.ajdailey.qcaret.app.command.ShortcutOverrides;
import io.github.ajdailey.qcaret.app.config.ConfigStore;
import io.github.ajdailey.qcaret.app.config.Options;
import io.github.ajdailey.qcaret.app.config.Preferences;
import io.github.ajdailey.qcaret.app.config.SettingsLocation;
import io.github.ajdailey.qcaret.app.config.SettingsMode;
import io.github.ajdailey.qcaret.app.theme.Palette;
import io.github.ajdailey.qcaret.app.theme.ThemeMode;
import io.github.ajdailey.qcaret.app.view.DialogSize;
import io.github.ajdailey.qcaret.app.view.PreferencesForm;
import io.github.ajdailey.qcaret.app.view.PreferencesInput;
import io.github.ajdailey.qcaret.app.view.SearchForm;
import io.github.ajdailey.qcaret.app.view.SearchScope;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Edit > Preferences and each setting's effect on the running window. */
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
        field.equals("poll") ? value : f.pollSeconds(),
        f.autoReloadClean(),
        field.equals("days") ? value : f.backupDays(),
        field.equals("lines") ? value : f.searchMaxLineChars(),
        field.equals("excluded") ? value : f.excludedDirectories(),
        f.theme(),
        field.equals("font") ? value : f.fontSize(),
        f.shortcuts());
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
                f.pollSeconds(),
                f.autoReloadClean(),
                f.backupDays(),
                f.searchMaxLineChars(),
                f.excludedDirectories(),
                ThemeMode.DARK,
                f.fontSize(),
                f.shortcuts())));
    assertEquals(Palette.DARK, harness.application.theme().palette());
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
                f.pollSeconds(),
                f.autoReloadClean(),
                f.backupDays(),
                f.searchMaxLineChars(),
                f.excludedDirectories(),
                f.theme(),
                f.fontSize(),
                overrides)));
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
        o.excludedDirectories());
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
