package io.github.ajdailey.qcaret.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.command.CommandGroup;
import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.command.KeyStroke;
import io.github.ajdailey.qcaret.app.command.Keymap;
import io.github.ajdailey.qcaret.app.config.ConfigStore;
import io.github.ajdailey.qcaret.app.config.Preferences;
import io.github.ajdailey.qcaret.app.config.SettingsLocation;
import io.github.ajdailey.qcaret.app.config.SettingsMode;
import io.github.ajdailey.qcaret.app.controller.ApplicationController;
import io.github.ajdailey.qcaret.app.controller.EditorController;
import io.github.ajdailey.qcaret.app.controller.WindowSet;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.macro.MacroStore;
import io.github.ajdailey.qcaret.app.theme.Palette;
import io.github.ajdailey.qcaret.app.theme.ThemeMode;
import io.github.ajdailey.qcaret.app.view.WindowGeometry;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.ui.swt.editor.SwtKeys;
import io.github.ajdailey.qcaret.ui.swt.platform.PlatformTraits;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.ToolItem;
import org.eclipse.swt.widgets.Widget;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Macros menu, its dialogs, the status-bar macro field, saved macros' palette and Preferences
 * rows, a saved macro's key, and a recording of editor commands under a real editor pane, under
 * Xvfb.
 */
@Tag("swt")
class MacroGuiTest {
  private static final Duration BOUND = Duration.ofSeconds(20);
  private static final KeyStroke KEY = KeyStroke.of(KeyStroke.PRIMARY | KeyStroke.SHIFT, 'j');

  @TempDir Path temp;

  private final List<Throwable> failures = new CopyOnWriteArrayList<Throwable>();
  private Display display;
  private Workers workers;
  private WindowSet set;
  private MainWindow window;
  private ApplicationController controller;
  private EditorController editor;

  @BeforeEach
  void open() throws Exception {
    Path macros = Files.createDirectories(temp.resolve("conf").resolve(MacroStore.DIRECTORY));
    Files.writeString(
        macros.resolve("Wrap" + MacroStore.SUFFIX),
        "qcaret-macro 1\nkey " + KEY.stored() + "\ntype \"<\"\n",
        StandardCharsets.UTF_8);
    display = new Display();
    workers =
        new Workers(
            2,
            new Thread.UncaughtExceptionHandler() {
              @Override
              public void uncaughtException(Thread thread, Throwable error) {
                failures.add(error);
              }
            });
    set =
        new WindowSet(
            workers,
            new SwtUiDispatcher(display),
            new ConfigStore(new SettingsLocation(SettingsMode.EXPLICIT, temp.resolve("conf"))),
            Preferences.DEFAULTS,
            SwtThemeService.open(display, ThemeMode.LIGHT),
            new MemoryBudget(Runtime.getRuntime().maxMemory(), 60),
            Keymap.defaults(PlatformTraits.PLATFORM.macKeys()),
            false);
    window = new MainWindow(display, Palette.LIGHT);
    controller = new ApplicationController(set, window);
    window.attach(controller);
    window.place(new WindowGeometry.Request(0, 0, 720, 480), null);
    window.open();
    Path file = Files.writeString(temp.resolve("text.txt"), "one\ntwo\nthree\n");
    controller.documents().open(List.of(file));
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return controller.documents().activeEditor() != null
                    && controller.keymap().macros().keys().key("Wrap") != null;
              }
            }),
        "document opens and the saved macro lists");
    editor = controller.documents().activeEditor();
  }

  @AfterEach
  void close() {
    set.quit();
    runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return window.shell().isDisposed();
          }
        });
    display.dispose();
    assertTrue(failures.isEmpty(), failures.toString());
  }

  private boolean runUntil(BooleanSupplier condition) {
    return EventLoop.runUntil(display, condition, BOUND);
  }

  private String text() {
    return editor.document().text().subSequence(0, editor.document().text().length()).toString();
  }

  private void awaitText(final String expected) {
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return text().equals(expected) && window.menuBar().states().savedMacroEnabled();
              }
            }),
        "run ends with " + expected + " but text was " + text());
  }

  private Menu macrosMenu() {
    return window.menuBar().menu(CommandGroup.MACROS);
  }

  private List<String> labels(Menu menu) {
    List<String> labels = new ArrayList<String>();
    for (MenuItem item : menu.getItems()) {
      labels.add((item.getStyle() & SWT.SEPARATOR) != 0 ? "-" : item.getText());
    }
    return labels;
  }

  private static void press(Widget widget) {
    widget.notifyListeners(SWT.Selection, new Event());
  }

  @Test
  void theMacrosMenuListsItsCommandsThenTheSavedMacrosWithTheirKeys() {
    Keymap keymap = controller.keymap();
    List<String> labels = labels(macrosMenu());
    String keys = keymap.macros().keyText("Wrap");
    assertEquals(
        List.of(
            CommandId.RECORD_MACRO.label() + "\t" + keymap.menuKeyText(CommandId.RECORD_MACRO),
            CommandId.PLAY_RECORDED_MACRO.label()
                + "\t"
                + keymap.menuKeyText(CommandId.PLAY_RECORDED_MACRO),
            CommandId.RUN_MACRO_MULTIPLE.label(),
            "-",
            CommandId.SAVE_RECORDED_MACRO.label(),
            CommandId.MANAGE_MACROS.label(),
            "-",
            "Wrap\t" + keys),
        labels);
    final MenuItem wrap = macrosMenu().getItem(labels.size() - 1);
    assertEquals(SwtKeys.accelerator(KEY), wrap.getAccelerator());
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return wrap.getEnabled();
              }
            }),
        "the saved macro's item enables with the document");
    MenuItem record = window.menuBar().item(CommandId.RECORD_MACRO);
    assertFalse(record.getSelection());
    controller.execute(CommandId.RECORD_MACRO);
    window.menuBar().sync(CommandGroup.MACROS);
    assertTrue(record.getSelection());
    assertFalse(wrap.getEnabled());
    assertTrue(window.macroDialogs().status().getVisible());
    assertEquals(UiText.MACRO_STATUS_RECORDING.text(), window.macroDialogs().status().getText());
    controller.execute(CommandId.RECORD_MACRO);
    window.menuBar().sync(CommandGroup.MACROS);
    assertFalse(record.getSelection());
    assertFalse(window.macroDialogs().status().getVisible());
    press(wrap);
    awaitText("<one\ntwo\nthree\n");
  }

  @Test
  void aSavedMacroKeyRunsTheMacro() {
    PostedInput.assumeGtk();
    window.shell().forceActive();
    window.activeText().setFocus();
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return window.activeText().isFocusControl();
              }
            }),
        "editor focused");
    PostedInput.ctrlShift(display, 'j');
    awaitText("<one\ntwo\nthree\n");
  }

  /** G2: after a run the toolbar's Undo drew disabled while Edit > Undo worked. */
  @Test
  void aRunEnablesTheToolbarUndoOnceItsUndoStepCloses() {
    final ToolItem undo = window.mainToolBar().item(CommandId.UNDO);
    assertFalse(undo.getEnabled());
    controller.runMacro("Wrap");
    awaitText("<one\ntwo\nthree\n");
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return undo.getEnabled();
              }
            }),
        "the toolbar's Undo enables");
  }

  private MenuItem savedItem() {
    return macrosMenu().getItem(macrosMenu().getItemCount() - 1);
  }

  private void awaitEnabled(final MenuItem item, String what) {
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return item.getEnabled();
              }
            }),
        what);
  }

  private void awaitRecording(final boolean recording) {
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return window.menuBar().states().checked(CommandId.RECORD_MACRO) == recording;
              }
            }),
        recording ? "recording starts" : "recording stops");
  }

  /**
   * G2 MACROKEYS: a run's first edit synced the menu with the run active, which disabled the Macros
   * items, and nothing enabled them after the run, so their accelerators did not fire.
   */
  @Test
  void theMacrosItemsEnableAfterARunAndARecording() {
    MenuItem record = window.menuBar().item(CommandId.RECORD_MACRO);
    MenuItem play = window.menuBar().item(CommandId.PLAY_RECORDED_MACRO);
    awaitEnabled(savedItem(), "the saved macro's item enables with the document");
    controller.runMacro("Wrap");
    awaitText("<one\ntwo\nthree\n");
    flush();
    assertTrue(record.getEnabled(), "Record Macro after a run");
    assertTrue(savedItem().getEnabled(), "the saved macro after a run");
    controller.execute(CommandId.RECORD_MACRO);
    editor.type("a");
    controller.execute(CommandId.RECORD_MACRO);
    flush();
    assertTrue(play.getEnabled(), "Play Recorded Macro after a recording");
    assertTrue(savedItem().getEnabled(), "the saved macro after a recording");
    controller.execute(CommandId.PLAY_RECORDED_MACRO);
    awaitText("<aaone\ntwo\nthree\n");
    flush();
    assertTrue(play.getEnabled(), "Play Recorded Macro after a run");
    assertTrue(record.getEnabled(), "Record Macro after a run");
    assertTrue(savedItem().getEnabled(), "the saved macro after a run");
  }

  /** G2 MACROKEYS: the play, saved macro and record keys act after a keyed run. */
  @Test
  void theMacroKeysActAfterAKeyedRun() {
    PostedInput.assumeGtk();
    window.shell().forceActive();
    window.activeText().setFocus();
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return window.activeText().isFocusControl();
              }
            }),
        "editor focused");
    awaitEnabled(savedItem(), "the saved macro's item enables with the document");
    PostedInput.ctrlShift(display, 'j');
    awaitText("<one\ntwo\nthree\n");
    PostedInput.ctrlShift(display, 'j');
    awaitText("<<one\ntwo\nthree\n");
    PostedInput.ctrlShift(display, 'r');
    awaitRecording(true);
    editor.type("a");
    PostedInput.ctrlShift(display, 'r');
    awaitRecording(false);
    PostedInput.ctrlShift(display, 'e');
    awaitText("<<aaone\ntwo\nthree\n");
    PostedInput.ctrlShift(display, 'r');
    awaitRecording(true);
    controller.execute(CommandId.RECORD_MACRO);
  }

  @Test
  void runMultipleTimesChecksTheCountAndRunsTheChosenMacro() {
    controller.execute(CommandId.RUN_MACRO_MULTIPLE);
    RunMacroDialog dialog = window.macroDialogs().runDialog();
    assertNotNull(dialog);
    assertEquals("Wrap", dialog.macro().getText());
    dialog.count().setText("x");
    assertFalse(dialog.ok().getEnabled());
    assertEquals(UiText.RUN_MACRO_COUNT_RANGE.format(999_999), dialog.problem().getText());
    dialog.count().setText("3");
    assertTrue(dialog.ok().getEnabled());
    dialog.submit();
    assertTrue(dialog.shell().isDisposed());
    awaitText("<<<one\ntwo\nthree\n");
  }

  @Test
  void saveRecordedMacroChecksTheKeyAndWritesTheFile() {
    controller.execute(CommandId.RECORD_MACRO);
    editor.type("ab");
    controller.execute(CommandId.RECORD_MACRO);
    controller.execute(CommandId.SAVE_RECORDED_MACRO);
    SaveMacroDialog dialog = window.macroDialogs().saveDialog();
    assertFalse(dialog.ok().getEnabled());
    dialog.name().setText("Twice");
    assertTrue(dialog.ok().getEnabled());
    dialog.key().set(KEY);
    assertFalse(dialog.ok().getEnabled());
    assertTrue(dialog.problem().getText().contains(UiText.MACRO_ROW.format("Wrap")));
    dialog.key().set(null);
    Menu bar = window.shell().getMenuBar();
    dialog.submit();
    final Path file = temp.resolve("conf").resolve(MacroStore.DIRECTORY).resolve("Twice.qcmacro");
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return controller.keymap().macros().keys().entries().size() == 2;
              }
            }),
        "the saved macro lists");
    assertTrue(Files.exists(file));
    assertTrue(labels(macrosMenu()).contains("Twice"), labels(macrosMenu()).toString());
    // A change of the saved macros alone keeps the bar; GTK 4 crashed after a replaced one.
    assertSame(bar, window.shell().getMenuBar());
  }

  @Test
  void manageMacrosRenamesAndDeletesAfterAsking() {
    controller.execute(CommandId.MANAGE_MACROS);
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return window.macroDialogs().manageDialog() != null;
              }
            }),
        "Manage Macros opens");
    final ManageMacrosDialog dialog = window.macroDialogs().manageDialog();
    final Table table = dialog.table();
    assertEquals(1, table.getItemCount());
    assertEquals("Wrap", table.getItem(0).getText(0));
    assertEquals(controller.keymap().macros().keyText("Wrap"), table.getItem(0).getText(1));
    assertEquals("Wrap", dialog.name().getText());
    dialog.name().setText("con");
    dialog.renameSelected();
    assertEquals(UiText.MACRO_NAME_DEVICE.format("con"), dialog.message().getText());
    dialog.name().setText("Angle");
    dialog.renameSelected();
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return table.getItemCount() == 1 && "Angle".equals(table.getItem(0).getText(0));
              }
            }),
        "renamed");
    assertTrue(labels(macrosMenu()).get(labels(macrosMenu()).size() - 1).startsWith("Angle\t"));
    dialog.deleteSelected();
    Shell ask = null;
    for (Shell child : dialog.shell().getShells()) {
      if (UiText.DELETE_TITLE.text().equals(child.getText())) {
        ask = child;
      }
    }
    assertNotNull(ask, "Delete asks first");
    press(findButton(ask, UiText.BUTTON_DELETE.text()));
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return table.getItemCount() == 0;
              }
            }),
        "deleted");
    assertEquals(6, labels(macrosMenu()).size(), labels(macrosMenu()).toString());
    dialog.shell().dispose();
  }

  private static Button findButton(Control parent, String text) {
    if (parent instanceof Button button && text.equals(button.getText())) {
      return button;
    }
    if (parent instanceof Composite composite) {
      for (Control child : composite.getChildren()) {
        Button found = findButton(child, text);
        if (found != null) {
          return found;
        }
      }
    }
    return null;
  }

  @Test
  void thePaletteAndShortcutListsShowSavedMacros() {
    controller.execute(CommandId.COMMAND_PALETTE);
    CommandPaletteWindow palette = window.commandPalette();
    palette.filter().setText("macros: wrap");
    Table rows = palette.table();
    assertTrue(rows.getItemCount() > 0);
    assertEquals(UiText.MACRO_ROW.format("Wrap"), rows.getItem(0).getText(0));
    rows.setSelection(0);
    rows.notifyListeners(SWT.DefaultSelection, new Event());
    awaitText("<one\ntwo\nthree\n");
    assertTrue(controller.keymap().reference().contains(UiText.MACRO_ROW.format("Wrap")));
    controller.execute(CommandId.PREFERENCES);
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return window.preferencesDialog() != null;
              }
            }),
        "Preferences opens");
    PreferencesDialog preferences = window.preferencesDialog();
    preferences.select(CommandId.PLAY_RECORDED_MACRO);
    preferences.record(KEY);
    preferences.addCaptured();
    assertEquals(
        UiText.PREFERENCES_KEY_TAKEN.format(
            controller.keymap().keyText(KEY), UiText.MACRO_ROW.format("Wrap")),
        preferences.keyMessage().getText());
    preferences.shell().dispose();
  }

  /**
   * Commands and typing during a recording move the widget's caret and selection, which an editor
   * pane reports back to the controller; none of those reports may count as a mouse action.
   */
  @Test
  void keyedEditingUnderARealPaneShowsNoMouseNotice() {
    final String mouse = UiText.MACRO_NOT_RECORDED_MOUSE.text();
    controller.execute(CommandId.RECORD_MACRO);
    List<String> notices = new ArrayList<String>();
    CommandId[] commands = {
      CommandId.LINE_END,
      CommandId.CARET_DOWN,
      CommandId.CARET_UP,
      CommandId.SELECT_DOWN,
      CommandId.SELECT_WORD_LEFT,
      CommandId.CARET_RIGHT,
      CommandId.ADD_CARET_BELOW,
      CommandId.CANCEL,
      CommandId.BLOCK_DOWN,
      CommandId.BLOCK_RIGHT,
      CommandId.CANCEL,
      CommandId.DOCUMENT_END,
      CommandId.PAGE_UP,
      CommandId.SELECT_LINE,
      CommandId.DUPLICATE,
      CommandId.MOVE_LINES_DOWN,
      CommandId.DOCUMENT_START
    };
    for (CommandId command : commands) {
      controller.execute(command);
      flush();
      notices.add(window.noticeText());
      editor.type("x");
      flush();
      notices.add(window.noticeText());
    }
    controller.execute(CommandId.RECORD_MACRO);
    assertFalse(notices.contains(mouse), notices.toString());
    assertNotEquals(mouse, window.noticeText());
  }

  private void flush() {
    for (int i = 0; i < 20 && display.readAndDispatch(); i++) {
      // Runs the posted repaint and selection events.
    }
  }
}
