package io.github.ajdailey.qcaret.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.ajdailey.qcaret.app.command.CommandGroup;
import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.command.Keymap;
import io.github.ajdailey.qcaret.app.config.ConfigStore;
import io.github.ajdailey.qcaret.app.config.Preferences;
import io.github.ajdailey.qcaret.app.config.SettingsLocation;
import io.github.ajdailey.qcaret.app.config.SettingsMode;
import io.github.ajdailey.qcaret.app.controller.ApplicationController;
import io.github.ajdailey.qcaret.app.controller.EditorController;
import io.github.ajdailey.qcaret.app.editor.ColumnMetrics;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.log.LogFiles;
import io.github.ajdailey.qcaret.app.log.RecentLog;
import io.github.ajdailey.qcaret.app.theme.Palette;
import io.github.ajdailey.qcaret.app.theme.ThemeMode;
import io.github.ajdailey.qcaret.app.view.WindowGeometry;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.document.Selection;
import io.github.ajdailey.qcaret.core.document.SelectionSet;
import io.github.ajdailey.qcaret.ui.swt.editor.EditorPane;
import io.github.ajdailey.qcaret.ui.swt.editor.SwtKeys;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The application shell in a real window: menu enablement without a menu shown, the encoding
 * cascades, the reused tab menu, an in-place font change, the status bar, the warning marker, the
 * busy cursor, the binary read-only view, and a secondary caret at column 150.
 */
@Tag("swt")
class ShellWindowTest {
  private static final Duration BOUND = Duration.ofSeconds(30);

  @TempDir Path temp;

  private Display display;
  private Workers workers;
  private MainWindow window;
  private ApplicationController controller;
  private final RecentLog recent = new RecentLog(50, 200);
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
            Keymap.defaults(SwtKeys.mac()),
            false);
    controller.setLogging(recent, LogFiles.NONE);
    window.attach(controller);
    window.place(new WindowGeometry.Request(null, null, 800, 500), null);
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
    Path file = temp.resolve(name);
    Files.writeString(file, text, StandardCharsets.UTF_8);
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

  private MenuItem item(CommandId command) {
    return window.menuBar().item(command);
  }

  @Test
  void menuEnablementFollowsDocumentsWithoutAMenuShown() throws Exception {
    assertFalse(item(CommandId.SAVE).getEnabled());
    assertFalse(item(CommandId.UNDO).getEnabled());
    assertTrue(item(CommandId.NEW).getEnabled());
    openFile("a.txt", "alpha\n");
    // No menu was shown: the document event alone enables the items, so their keys fire.
    assertTrue(item(CommandId.SAVE).getEnabled());
    assertTrue(item(CommandId.UNDO).getEnabled());
    assertFalse(item(CommandId.NEXT_TAB).getEnabled());
    openFile("b.txt", "beta\n");
    assertTrue(item(CommandId.NEXT_TAB).getEnabled());
    assertTrue(item(CommandId.SAVE_AS).getText().contains("\t"));
    controller.execute(CommandId.CLOSE_ALL);
    drain();
    assertFalse(item(CommandId.SAVE).getEnabled());
  }

  @Test
  void theEncodingCascadesFillOnceTheCharsetsAreListed() throws Exception {
    final MenuItem other = window.menuBar().otherEncodings(true);
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return other.getEnabled();
          }
        },
        "charsets listed");
    int count = other.getMenu().getItemCount();
    assertTrue(count > 10, "other charsets " + count);
    assertEquals(count, window.menuBar().otherEncodings(false).getMenu().getItemCount());
    Menu encoding = window.menuBar().menu(CommandGroup.ENCODING);
    encoding.notifyListeners(SWT.Show, new Event());
    assertEquals(count, other.getMenu().getItemCount());
  }

  @Test
  void oneTabMenuServesEveryTabAndADraggedTabKeepsItsDocument() throws Exception {
    openFile("one.txt", "one");
    openFile("two.txt", "two");
    Menu first = window.showTabMenu(0);
    first.setVisible(false);
    Menu second = window.showTabMenu(1);
    second.setVisible(false);
    assertSame(first, second);
    assertEquals(8, second.getItemCount());
    assertTrue(second.getItem(1).getEnabled());
    EditorPane shown = window.activeEditor();
    window.moveTab(window.tabs().getSelection(), 0);
    Menu again = window.showTabMenu(0);
    again.setVisible(false);
    assertSame(first, again);
    assertSame(shown, window.activeEditor());
    assertEquals("two.txt", window.tabs().getItem(0).getText());
    // Copy File Name on the dragged tab.
    again.getItem(7).notifyListeners(SWT.Selection, new Event());
    assertEquals("two.txt", window.clipboardText());
  }

  @Test
  void aFontChangeKeepsTheWidgetsAndTheFocus() throws Exception {
    openFile("font.txt", "some text\n");
    StyledText text = window.activeText();
    Menu bar = window.shell().getMenuBar();
    text.setFocus();
    drain();
    int height = text.getLineHeight();
    int gutter = window.activeEditor().gutterWidth();
    for (int i = 0; i < 6; i++) {
      controller.execute(CommandId.ZOOM_IN);
    }
    drain();
    assertSame(text, window.activeText());
    assertSame(bar, window.shell().getMenuBar());
    assertSame(window.editorFont(), text.getFont());
    assertTrue(text.getLineHeight() > height, height + " -> " + text.getLineHeight());
    assertTrue(window.activeEditor().gutterWidth() > gutter);
    Control focus = display.getFocusControl();
    assumeTrue(focus != null, "no focus under this display");
    assertSame(text, focus);
  }

  @Test
  void theStatusBarShowsTheCaretAndTheDocument() throws Exception {
    openFile("status.txt", "ab\tc\r\nline two\r\n");
    final StyledText text = window.activeText();
    controller.documents().activeEditor().selectFromView(0, 4);
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return window.statusText().contains(UiText.STATUS_POSITION.format(1, 6));
          }
        },
        "status " + window.statusText());
    assertEquals(
        "|"
            + UiText.STATUS_POSITION.format(1, 6)
            + "|"
            + UiText.STATUS_SELECTION.format(4)
            + "|UTF-8|"
            + UiText.STATUS_CRLF.text()
            + "|"
            + UiText.STATUS_PLAIN_TEXT.text(),
        window.statusText());
    assertNotNull(text);
  }

  @Test
  void aWarningShowsTheMarkerUntilShowLog() throws Exception {
    assertFalse(window.warningMarkerShown());
    recent.append("WARN mock warning", true);
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return window.warningMarkerShown();
          }
        },
        "marker");
    controller.execute(CommandId.SHOW_LOG);
    assertFalse(window.warningMarkerShown());
    assertNotNull(window.logWindow());
    controller.execute(CommandId.SHOW_LOG);
    assertEquals(1, window.shell().getShells().length);
    window.logWindow().shell().dispose();
  }

  @Test
  void theBusyCursorCountsOverlappingOperations() {
    window.setActive();
    window.setActive();
    window.setIdle();
    drain();
    assertNotNull(window.shell().getCursor());
    window.setIdle();
    drain();
    assertNull(window.shell().getCursor());
  }

  @Test
  void aBinaryFileOpensAsAMarkedReadOnlyView() throws Exception {
    Path file = temp.resolve("blob.bin");
    byte[] bytes = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n', 0, 0, 0, 13, 'I', 'H'};
    Files.write(file, bytes);
    controller.documents().open(List.of(file));
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return window.shell().getShells().length == 1;
          }
        },
        "binary prompt");
    Shell prompt = window.shell().getShells()[0];
    Button open = null;
    for (Control child : prompt.getChildren()) {
      open = open != null ? open : find(child, UiText.BUTTON_OPEN_READ_ONLY.text());
    }
    assertNotNull(open);
    open.notifyListeners(SWT.Selection, new Event());
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return window.tabs().getItemCount() == 1 && controller.documents().loading() == 0;
          }
        },
        "binary view");
    drain();
    assertEquals(UiText.TAB_BINARY.format("blob.bin"), window.tabs().getItem(0).getText());
    assertFalse(item(CommandId.SAVE).getEnabled());
    assertFalse(item(CommandId.SAVE_AS).getEnabled());
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return window.statusText().startsWith(UiText.STATUS_BINARY.text());
          }
        },
        "status " + window.statusText());
    assertTrue(window.statusText().contains("ISO-8859-1"));
    StyledText text = window.activeText();
    Event key = new Event();
    key.character = 'x';
    key.keyCode = 'x';
    text.notifyListeners(SWT.KeyDown, key);
    assertEquals(bytes.length, Files.size(file));
    assertEquals(
        new String(bytes, StandardCharsets.ISO_8859_1),
        controller.documents().activeEditor().document().text().toString());
  }

  private static Button find(Control control, String label) {
    if (control instanceof Button button && label.equals(button.getText())) {
      return button;
    }
    if (control instanceof org.eclipse.swt.widgets.Composite composite) {
      for (Control child : composite.getChildren()) {
        Button found = find(child, label);
        if (found != null) {
          return found;
        }
      }
    }
    return null;
  }

  /** QLogg UI-1 and TEST-10: geometry and painting checked at column 150, not near column 0. */
  @Test
  void columnsAndASecondaryCaretHoldAtColumn150() throws Exception {
    openFile("wide.txt", "x".repeat(200) + "\nsecond\n");
    StyledText text = window.activeText();
    EditorPane pane = window.activeEditor();
    controller.execute(CommandId.ZOOM_IN);
    drain();
    ColumnMetrics metrics = pane.metrics();
    int left = text.getLocationAtOffset(0).x;
    int x150 = text.getLocationAtOffset(150).x;
    assertTrue(
        Math.abs(x150 - left - metrics.x(150)) <= 1,
        "widget x " + (x150 - left) + ", metrics x " + metrics.x(150));
    EditorController editor = controller.documents().activeEditor();
    editor.selections().set(SelectionSet.of(List.of(Selection.caret(1), Selection.caret(150)), 0));
    text.setHorizontalIndex(0);
    window.shell().setSize(Math.max(window.shell().getSize().x, x150 + 200), 400);
    drain();
    final Point at = text.getLocationAtOffset(150);
    assumeTrue(at.x + 4 < text.getClientArea().width, "column 150 is off screen");
    Point size = text.getSize();
    Image image = new Image(display, size.x, size.y);
    GC gc = new GC(image);
    boolean printed;
    try {
      printed = text.print(gc);
    } finally {
      gc.dispose();
    }
    // Control.print returns false under GTK 4 (SWT issue 2819: no offscreen draw of a control).
    assumeTrue(printed, "Control.print not supported here");
    ImageData data = image.getImageData();
    image.dispose();
    RGB ink =
        new RGB(
            Palette.LIGHT.caret().red(),
            Palette.LIGHT.caret().green(),
            Palette.LIGHT.caret().blue());
    int lineHeight = text.getLineHeight();
    int dark = 0;
    for (int y = at.y + 1; y < at.y + lineHeight - 1; y++) {
      if (ink.equals(data.palette.getRGB(data.getPixel(at.x, y)))) {
        dark++;
      }
    }
    assertTrue(dark >= lineHeight - 3, "caret pixels " + dark + " of " + lineHeight);
    assertNotEquals(0, at.x);
  }
}
