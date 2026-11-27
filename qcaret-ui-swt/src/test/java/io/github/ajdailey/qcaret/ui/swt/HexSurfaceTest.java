package io.github.ajdailey.qcaret.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.command.Keymap;
import io.github.ajdailey.qcaret.app.config.ConfigStore;
import io.github.ajdailey.qcaret.app.config.Preferences;
import io.github.ajdailey.qcaret.app.config.SettingsLocation;
import io.github.ajdailey.qcaret.app.config.SettingsMode;
import io.github.ajdailey.qcaret.app.controller.ApplicationController;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.theme.Palette;
import io.github.ajdailey.qcaret.app.theme.ThemeMode;
import io.github.ajdailey.qcaret.app.view.HexMode;
import io.github.ajdailey.qcaret.app.view.MarkdownViewMode;
import io.github.ajdailey.qcaret.app.view.WindowGeometry;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.ui.swt.hex.HexPane;
import io.github.ajdailey.qcaret.ui.swt.platform.PlatformTraits;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The hex views in a main window: a tab's pane right of the editors in Split, alone in Hex and
 * hidden in Text, a click and a key moving the editor's caret, the pane and the Markdown preview
 * never shown together; the binary-file prompt's three buttons, Open in Hex Viewer opening a window
 * over the file and Cancel opening nothing; Go to Offset and the find bar from the focused pane; a
 * theme and a font switch reaching the pane; the View, File and Preferences controls.
 */
@Tag("swt")
class HexSurfaceTest {
  private static final Duration BOUND = Duration.ofSeconds(30);

  @TempDir Path temp;

  private Display display;
  private Workers workers;
  private MainWindow window;
  private ApplicationController controller;
  private final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();

  @BeforeEach
  void open() {
    display = new Display();
    workers =
        new Workers(
            2,
            new Thread.UncaughtExceptionHandler() {
              @Override
              public void uncaughtException(Thread thread, Throwable error) {
                failure.set(error);
              }
            });
    window = new MainWindow(display, Palette.LIGHT);
    controller =
        new ApplicationController(
            workers,
            new SwtUiDispatcher(display),
            window,
            new ConfigStore(new SettingsLocation(SettingsMode.EXPLICIT, temp.resolve("conf"))),
            Preferences.DEFAULTS,
            SwtThemeService.open(display, ThemeMode.LIGHT),
            new MemoryBudget(Runtime.getRuntime().maxMemory(), 60),
            Keymap.defaults(PlatformTraits.PLATFORM.macKeys()),
            false);
    window.attach(controller);
    window.place(new WindowGeometry.Request(null, null, 1100, 600), null);
    window.open();
  }

  @AfterEach
  void close() {
    try {
      controller.documents().dispose();
      workers.close();
      display.dispose();
    } finally {
      assertNull(failure.get());
    }
  }

  private void await(BooleanSupplier condition, String what) {
    assertTrue(EventLoop.runUntil(display, condition, BOUND), what);
  }

  private void drain() {
    while (display.readAndDispatch()) {
      // Runs queued work.
    }
  }

  private Path openFile(String name, String text) throws Exception {
    Path file = Files.writeString(temp.resolve(name), text, StandardCharsets.UTF_8);
    final int before = window.tabs().getItemCount();
    controller.documents().open(List.of(file));
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return window.tabs().getItemCount() == before + 1
                && controller.documents().loading() == 0;
          }
        },
        "opened " + name);
    drain();
    return file;
  }

  private EditorTab tab() {
    return (EditorTab) window.tabs().getSelection().getData();
  }

  /** Runs a mode command and waits for the pane to show the bytes. */
  private HexPane show(CommandId command, final long size) {
    controller.execute(command);
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            List<HexPane> panes = window.hexViews().panes();
            return !panes.isEmpty() && panes.get(panes.size() - 1).rows().size() == size;
          }
        },
        "the pane's bytes");
    drain();
    List<HexPane> panes = window.hexViews().panes();
    return panes.get(panes.size() - 1);
  }

  private int editorCaret() {
    return controller.documents().activeEditor().selections().selections().primary().caret();
  }

  private static void mouse(Canvas canvas, int type, int x, int y) {
    Event event = new Event();
    event.button = 1;
    event.x = x;
    event.y = y;
    canvas.notifyListeners(type, event);
  }

  private static void key(Canvas canvas, int keyCode) {
    Event event = new Event();
    event.keyCode = keyCode;
    canvas.notifyListeners(SWT.KeyDown, event);
  }

  @Test
  void splitHexAndTextPlaceThePaneAndItsCaretMovesTheEditor() throws Exception {
    openFile("a.txt", "0123456789abcdef\nline two\n");
    HexPane pane = show(CommandId.HEX_SPLIT, 26);
    EditorTab tab = tab();
    assertTrue(pane.control().getVisible());
    assertTrue(tab.sash.getVisible());
    assertNull(tab.frame.getMaximizedControl());
    assertTrue(
        pane.control().getBounds().x >= tab.sash.getBounds().x + tab.sash.getBounds().width,
        "pane right of the editors");
    assertTrue(pane.control().getBounds().width >= pane.preferredWidth() / 2);
    Canvas canvas = pane.canvas();
    int y = pane.lineHeight() + pane.lineHeight() / 2;
    mouse(canvas, SWT.MouseDown, pane.columnX(pane.rows().hexColumn(3)) + 1, y);
    mouse(canvas, SWT.MouseUp, 0, 0);
    assertEquals(3, editorCaret());
    key(canvas, SWT.ARROW_RIGHT);
    assertEquals(4, editorCaret());
    assertTrue(pane.status().getText().contains("0x00000004"), pane.status().getText());

    controller.execute(CommandId.HEX_HEX);
    drain();
    assertSame(pane.control(), tab.frame.getMaximizedControl());
    assertFalse(tab.sash.getVisible());
    assertEquals(HexMode.HEX, controller.documents().hexMode());

    controller.execute(CommandId.HEX_TEXT);
    drain();
    assertFalse(pane.control().getVisible());
    assertTrue(tab.sash.getVisible());
    assertSame(tab.sash, tab.frame.getMaximizedControl());
  }

  @Test
  void thePaneAndTheMarkdownPreviewNeverShowTogether() throws Exception {
    controller.loadGrammars();
    openFile("notes.md", "# Title\n\ntext\n");
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return controller.documents().activeMarkdown();
          }
        },
        "Markdown tab");
    controller.execute(CommandId.MARKDOWN_SPLIT);
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return window.activePreview() != null;
          }
        },
        "the preview");
    drain();
    Control preview = window.activePreview().control();
    assertTrue(preview.getVisible());
    HexPane pane = show(CommandId.HEX_SPLIT, 14);
    assertEquals(MarkdownViewMode.SOURCE, controller.documents().markdownMode());
    assertTrue(pane.control().getVisible());
    assertFalse(preview.getVisible());
    controller.execute(CommandId.MARKDOWN_SPLIT);
    drain();
    assertEquals(HexMode.TEXT, controller.documents().hexMode());
    assertFalse(pane.control().getVisible());
    assertTrue(preview.getVisible());
  }

  @Test
  void theBinaryPromptOpensTheHexViewerOrNothing() throws Exception {
    final Path file = temp.resolve("blob.bin");
    byte[] bytes = {(byte) 0x89, 'P', 'N', 'G', 0, 0, 0, 13, 'I', 'H', 'D', 'R', 0, 1};
    Files.write(file, bytes);
    Shell prompt = prompt(file);
    assertEquals(3, buttons(prompt).size());
    assertEquals(UiText.BUTTON_OPEN_IN_HEX_VIEWER.text(), prompt.getDefaultButton().getText());
    prompt.getDefaultButton().notifyListeners(SWT.Selection, new Event());
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            List<HexWindow> windows = window.hexViews().windows();
            return windows.size() == 1 && windows.get(0).pane().rows().size() == bytes.length;
          }
        },
        "the Hex Viewer");
    drain();
    HexWindow viewer = window.hexViews().windows().get(0);
    assertEquals(UiText.HEX_VIEWER_TITLE.format("blob.bin"), viewer.shell().getText());
    assertEquals(0, window.tabs().getItemCount());
    viewer.shell().close();
    drain();
    assertEquals(List.of(), window.hexViews().windows());

    prompt = prompt(file);
    for (Button button : buttons(prompt)) {
      if (button.getText().equals(UiText.BUTTON_CANCEL.text())) {
        button.notifyListeners(SWT.Selection, new Event());
      }
    }
    drain();
    assertEquals(0, window.tabs().getItemCount());
    assertEquals(List.of(), window.hexViews().windows());
  }

  private Shell prompt(Path file) {
    controller.documents().open(List.of(file));
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            for (Shell child : window.shell().getShells()) {
              if (child.getText().equals(UiText.BINARY_TITLE.text()) && !child.isDisposed()) {
                return true;
              }
            }
            return false;
          }
        },
        "binary prompt");
    for (Shell child : window.shell().getShells()) {
      if (child.getText().equals(UiText.BINARY_TITLE.text())) {
        return child;
      }
    }
    throw new AssertionError("no prompt");
  }

  private static List<Button> buttons(Composite parent) {
    List<Button> found = new ArrayList<Button>();
    for (Control child : parent.getChildren()) {
      if (child instanceof Button button) {
        found.add(button);
      } else if (child instanceof Composite composite) {
        found.addAll(buttons(composite));
      }
    }
    return found;
  }

  @Test
  void goToOffsetAndFindRunInTheFocusedPane() throws Exception {
    openFile("c.txt", "MZ header and more text\n");
    HexPane pane = show(CommandId.HEX_HEX, 24);
    pane.canvas().notifyListeners(SWT.FocusIn, new Event());
    controller.execute(CommandId.GO_TO_LINE);
    drain();
    Shell dialog = null;
    for (Shell child : window.shell().getShells()) {
      if (child.getText().equals(UiText.HEX_GO_TO_OFFSET_TITLE.text())) {
        dialog = child;
      }
    }
    assertNotNull(dialog, "Go to Offset");
    Text field = null;
    for (Control child : dialog.getChildren()) {
      if (child instanceof Text text) {
        field = text;
      }
    }
    assertNotNull(field);
    field.setText("0x5");
    dialog.getDefaultButton().notifyListeners(SWT.Selection, new Event());
    drain();
    assertEquals(5, editorCaret());

    // Xvfb has no window manager to give the focus back once the prompt closes.
    pane.canvas().notifyListeners(SWT.FocusIn, new Event());
    controller.execute(CommandId.FIND);
    drain();
    assertTrue(pane.findBar().getVisible());
    Text pattern = null;
    Button hex = null;
    for (Control child : pane.findBar().getChildren()) {
      if (child instanceof Text text) {
        pattern = text;
      } else if (child instanceof Button button && (button.getStyle() & SWT.CHECK) != 0) {
        hex = button;
      }
    }
    assertNotNull(pattern);
    assertNotNull(hex);
    pattern.setText("6D 6F");
    hex.setSelection(true);
    Event enter = new Event();
    enter.detail = SWT.TRAVERSE_RETURN;
    pattern.notifyListeners(SWT.Traverse, enter);
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return editorCaret() == 16;
          }
        },
        "the match, caret " + editorCaret());
  }

  @Test
  void themeFontAndBytesPerRowReachThePane() throws Exception {
    openFile("d.txt", "theme\n");
    HexPane pane = show(CommandId.HEX_SPLIT, 6);
    controller.execute(CommandId.THEME_DARK);
    drain();
    RGB dark =
        new RGB(
            Palette.DARK.background().red(),
            Palette.DARK.background().green(),
            Palette.DARK.background().blue());
    assertEquals(dark, pane.background());
    int before = pane.lineHeight();
    window.setFontSize(PlatformTraits.PLATFORM.monospaceSize() * 2);
    drain();
    assertTrue(pane.lineHeight() > before, before + " to " + pane.lineHeight());

    controller.execute(CommandId.PREFERENCES);
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return window.preferencesDialog() != null;
          }
        },
        "Preferences");
    PreferencesDialog dialog = window.preferencesDialog();
    Combo width = dialog.bytesPerRow();
    assertEquals(List.of("8", "16", "32"), List.of(width.getItems()));
    assertEquals("16", width.getText());
    width.select(2);
    assertTrue(dialog.apply());
    drain();
    assertEquals(32, pane.rows().bytesPerRow());
  }

  @Test
  void theMenusHoldTheHexCommands() throws Exception {
    MenuItem open = window.menuBar().item(CommandId.OPEN_IN_HEX_VIEWER);
    assertNotNull(open);
    assertTrue(open.getEnabled());
    openFile("e.txt", "menus\n");
    window.menuBar().syncAll();
    MenuItem cycle = window.menuBar().item(CommandId.HEX_CYCLE);
    assertTrue(cycle.getText().contains("\t"), cycle.getText());
    MenuItem split = window.menuBar().item(CommandId.HEX_SPLIT);
    assertTrue((split.getStyle() & SWT.RADIO) != 0);
    assertTrue(window.menuBar().item(CommandId.HEX_TEXT).getSelection());
    show(CommandId.HEX_CYCLE, 6);
    window.menuBar().syncAll();
    assertTrue(split.getSelection());
  }
}
