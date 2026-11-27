package io.github.ajdailey.qcaret.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.command.KeyStroke;
import io.github.ajdailey.qcaret.app.command.KeyTarget;
import io.github.ajdailey.qcaret.app.command.Keymap;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.macro.MacroNames;
import io.github.ajdailey.qcaret.app.macro.MacroStore;
import io.github.ajdailey.qcaret.app.view.MacroManagerInput;
import io.github.ajdailey.qcaret.app.view.PaletteEntry;
import io.github.ajdailey.qcaret.app.view.RunMacroForm;
import io.github.ajdailey.qcaret.app.view.SaveMacroForm;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Macros menu's dialog commands, saved macros' keys, palette rows and menu state, and the
 * status-bar macro field, through the window's controllers and {@link FakeView}.
 */
class MacroCommandsTest {
  private static final KeyStroke MOD_SHIFT_1 =
      KeyStroke.of(KeyStroke.PRIMARY | KeyStroke.SHIFT, '1');

  @TempDir Path temp;

  private MacroTesting t;

  @BeforeEach
  void create() {
    t = new MacroTesting(temp);
  }

  @AfterEach
  void close() throws InterruptedException {
    t.close();
  }

  private Path folder() {
    return t.app.windowSet().config().location().directory().resolve(MacroStore.DIRECTORY);
  }

  /** Records typed text as the recorded macro. */
  private void record(EditorController editor, String text) {
    t.app.execute(CommandId.RECORD_MACRO);
    editor.type(text);
    t.app.execute(CommandId.RECORD_MACRO);
  }

  /** Runs UI tasks until the saved macros list a name, or until they do not. */
  private void awaitListed(final String name, final boolean listed) throws InterruptedException {
    t.harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            t.harness.ui.runPending();
            return (t.macros().saved().macro(name) != null) == listed;
          }
        },
        name + " listed " + listed);
  }

  private void saveRecorded(String name, KeyStroke key) throws InterruptedException {
    t.app.execute(CommandId.SAVE_RECORDED_MACRO);
    SaveMacroForm form = new SaveMacroForm(name, key);
    assertNull(t.harness.view.saveMacroProblem.apply(form));
    t.harness.view.saveMacroAnswer.accept(form);
    awaitListed(name, true);
  }

  @Test
  void theMenuCommandsFollowTheRecordedAndSavedMacros() throws Exception {
    EditorController editor = t.open("abc\n");
    CommandStates states = new CommandStates(t.app);
    assertFalse(states.enabled(CommandId.RUN_MACRO_MULTIPLE));
    assertFalse(states.enabled(CommandId.SAVE_RECORDED_MACRO));
    assertTrue(states.enabled(CommandId.MANAGE_MACROS));
    assertTrue(states.savedMacroEnabled());
    t.app.execute(CommandId.RECORD_MACRO);
    assertEquals(UiText.MACRO_STATUS_RECORDING.text(), last(t.harness.view.macroStatus));
    assertFalse(states.savedMacroEnabled());
    editor.type("x");
    t.app.execute(CommandId.RECORD_MACRO);
    assertEquals("", last(t.harness.view.macroStatus));
    assertTrue(states.enabled(CommandId.RUN_MACRO_MULTIPLE));
    assertTrue(states.enabled(CommandId.SAVE_RECORDED_MACRO));
    t.app.execute(CommandId.READ_ONLY);
    assertFalse(states.enabled(CommandId.RUN_MACRO_MULTIPLE));
    assertFalse(states.savedMacroEnabled());
  }

  @Test
  void saveChecksTheNameAndKeyThenWritesTheFileAndBindsTheKey() throws Exception {
    EditorController editor = t.open("abc\n");
    record(editor, "x");
    t.app.execute(CommandId.SAVE_RECORDED_MACRO);
    assertEquals(new SaveMacroForm("", null), t.harness.view.saveMacroShown);
    assertEquals(
        UiText.MACRO_NAME_LENGTH.format(MacroNames.MAX_LENGTH),
        problem(new SaveMacroForm("", null)));
    assertEquals(UiText.MACRO_NAME_CHARS.text(), problem(new SaveMacroForm("a/b", null)));
    assertEquals(UiText.MACRO_NAME_DEVICE.format("con"), problem(new SaveMacroForm("con", null)));
    Keymap keymap = t.app.keymap();
    KeyStroke save = KeyStroke.of(KeyStroke.PRIMARY, 's');
    assertEquals(
        UiText.PREFERENCES_KEY_TAKEN.format(keymap.keyText(save), CommandId.SAVE.title()),
        problem(new SaveMacroForm("Wrap", save)));
    KeyStroke typing = KeyStroke.of(KeyStroke.SHIFT, 'q');
    assertEquals(
        UiText.PREFERENCES_KEY_TYPES.format(keymap.keyText(typing)),
        problem(new SaveMacroForm("Wrap", typing)));
    saveRecorded("Wrap", MOD_SHIFT_1);
    assertEquals(UiText.MACRO_SAVED.format("Wrap"), t.lastNotice());
    String file =
        Files.readString(folder().resolve("Wrap" + MacroStore.SUFFIX), StandardCharsets.UTF_8);
    assertTrue(file.contains("key " + MOD_SHIFT_1.stored()), file);
    assertTrue(file.contains("type \"x\""), file);
    keymap = t.app.keymap();
    assertEquals(t.harness.view.keymap, keymap);
    assertEquals(KeyTarget.macro("Wrap"), keymap.macros().windowBindingTarget(MOD_SHIFT_1));
    assertEquals(MOD_SHIFT_1, keymap.macros().accelerator("Wrap"));
    assertEquals(UiText.MACRO_NAME_TAKEN.format("Wrap"), problem(new SaveMacroForm("WRAP", null)));
    assertEquals(
        UiText.PREFERENCES_KEY_TAKEN.format(
            keymap.keyText(MOD_SHIFT_1), UiText.MACRO_ROW.format("Wrap")),
        problem(new SaveMacroForm("Other", MOD_SHIFT_1)));
  }

  private String problem(SaveMacroForm form) {
    return t.harness.view.saveMacroProblem.apply(form);
  }

  @Test
  void aSavedMacroRunsFromItsNameAndThePalette() throws Exception {
    EditorController editor = t.open("abc\n");
    record(editor, "x");
    saveRecorded("Ex", null);
    t.app.runMacro("Ex");
    t.settle(editor);
    assertEquals("xxabc\n", MacroTesting.text(editor));
    t.app.execute(CommandId.COMMAND_PALETTE);
    List<PaletteEntry> rows = t.harness.view.palette.filter("macros: ex");
    PaletteEntry row = rows.get(0);
    assertEquals(UiText.MACRO_ROW.format("Ex"), row.title());
    assertEquals("Ex", row.macro());
    assertNull(row.command());
    assertTrue(row.enabled());
    t.harness.view.palette.runMacro("Ex");
    t.settle(editor);
    assertEquals("xxxabc\n", MacroTesting.text(editor));
    t.app.runMacro("Missing");
    assertEquals(UiText.MACRO_NONE.text(), t.lastNotice());
  }

  @Test
  void runMultipleTimesRunsACountOrToTheEndAndShowsProgress() throws Exception {
    EditorController editor = t.open("a\nb\nc\nd\n");
    t.app.execute(CommandId.RECORD_MACRO);
    editor.type("-");
    t.app.execute(CommandId.CARET_DOWN);
    t.app.execute(CommandId.LINE_START);
    t.app.execute(CommandId.RECORD_MACRO);
    saveRecorded("Dash", null);
    t.app.execute(CommandId.RUN_MACRO_MULTIPLE);
    RunMacroForm shown = t.harness.view.runMacroShown;
    assertEquals(List.of(UiText.MACRO_RECORDED_CHOICE.text(), "Dash"), shown.macros());
    assertEquals(
        UiText.RUN_MACRO_COUNT_RANGE.format(MacroController.MAX_COUNT),
        t.harness.view.runMacroProblem.apply(new RunMacroForm(shown.macros(), 1, "0", false)));
    assertNull(
        t.harness.view.runMacroProblem.apply(new RunMacroForm(shown.macros(), 1, "x", true)));
    t.harness.view.macroStatus.clear();
    t.harness.view.runMacroAnswer.accept(new RunMacroForm(shown.macros(), 1, "2", false));
    // One step per UI turn, so the run reports progress between turns.
    t.macros()
        .run(t.app)
        .setNanos(
            new LongSupplier() {
              private long now;

              @Override
              public long getAsLong() {
                now += MacroPlayer.TURN_NANOS;
                return now;
              }
            });
    t.settle(editor);
    awaitStatusCleared();
    assertEquals("-a\n-b\n-c\nd\n", MacroTesting.text(editor));
    assertTrue(t.harness.view.macroStatus.contains(UiText.MACRO_STATUS_COUNT.format(2, 2)));
    t.app.execute(CommandId.RUN_MACRO_MULTIPLE);
    t.harness.view.runMacroAnswer.accept(new RunMacroForm(shown.macros(), 0, "1", true));
    t.settle(editor);
    awaitStatusCleared();
    assertEquals("-a\n-b\n-c\n-d\n", MacroTesting.text(editor));
  }

  private void awaitStatusCleared() throws InterruptedException {
    t.harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            t.harness.ui.runPending();
            return t.macros().run(t.app) == null && "".equals(last(t.harness.view.macroStatus));
          }
        },
        "run ends");
  }

  @Test
  void manageMacrosRenamesSetsKeysDeletesAndListsUnreadableFiles() throws Exception {
    EditorController editor = t.open("abc\n");
    record(editor, "x");
    saveRecorded("One", null);
    Files.writeString(folder().resolve("Broken" + MacroStore.SUFFIX), "not a macro\n");
    t.app.execute(CommandId.MANAGE_MACROS);
    t.harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            t.harness.ui.runPending();
            return t.harness.view.macroManager != null;
          }
        },
        "Manage Macros opens");
    MacroManagerInput manager = t.harness.view.macroManager;
    List<MacroManagerInput.Row> rows = manager.rows();
    assertEquals(2, rows.size());
    assertEquals("Broken", rows.get(0).name());
    assertFalse(rows.get(0).problem().isEmpty());
    assertEquals(new MacroManagerInput.Row("One", null, ""), rows.get(1));
    assertEquals(UiText.MACRO_UNREADABLE_KEY.text(), manager.setKey("Broken", MOD_SHIFT_1));
    assertEquals(UiText.MACRO_NAME_TAKEN.format("Broken"), manager.rename("One", "broken"));
    assertNull(manager.setKey("One", MOD_SHIFT_1));
    awaitKey("One", MOD_SHIFT_1);
    assertNull(manager.rename("One", "Two"));
    awaitListed("Two", true);
    assertEquals(MOD_SHIFT_1, t.app.keymap().macros().accelerator("Two"));
    assertNull(manager.setKey("Two", null));
    awaitKey("Two", null);
    manager.delete("Two");
    awaitListed("Two", false);
    assertFalse(Files.exists(folder().resolve("Two" + MacroStore.SUFFIX)));
    assertTrue(t.harness.view.macroRowsChanged >= 4);
    assertEquals(List.of(), t.harness.view.macroMessages);
    assertNotNull(manager.rows());
  }

  private void awaitKey(final String name, final KeyStroke key) throws InterruptedException {
    t.harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            t.harness.ui.runPending();
            KeyStroke now = t.app.keymap().macros().keys().key(name);
            return t.macros().saved().macro(name) != null
                && (key == null ? now == null : key.equals(now));
          }
        },
        name + " key");
  }

  @Test
  void aMacroKeyACommandHoldsStaysWithTheCommandAndIsAConflict() throws Exception {
    EditorController editor = t.open("abc\n");
    record(editor, "x");
    KeyStroke save = KeyStroke.of(KeyStroke.PRIMARY, 's');
    Files.createDirectories(folder());
    Files.writeString(
        folder().resolve("Clash" + MacroStore.SUFFIX),
        "qcaret-macro 1\nkey " + save.stored() + "\ntype \"y\"\n");
    t.app.execute(CommandId.MANAGE_MACROS);
    awaitListed("Clash", true);
    Keymap keymap = t.app.keymap();
    assertNull(keymap.macros().accelerator("Clash"));
    assertEquals(KeyTarget.of(CommandId.SAVE), keymap.macros().windowBindingTarget(save));
    assertTrue(
        keymap
            .conflicts()
            .contains(
                UiText.SHORTCUTS_CONFLICT_LINE.format(
                    keymap.keyText(save),
                    CommandId.SAVE.title(),
                    UiText.MACRO_ROW.format("Clash"))),
        keymap.conflicts().toString());
  }

  private static String last(List<String> list) {
    return list.isEmpty() ? null : list.get(list.size() - 1);
  }
}
