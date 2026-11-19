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
import io.github.ajdailey.qcaret.app.view.BookmarksInput;
import io.github.ajdailey.qcaret.app.view.ResultsInput;
import io.github.ajdailey.qcaret.app.view.SearchForm;
import io.github.ajdailey.qcaret.app.view.SearchScope;
import io.github.ajdailey.qcaret.app.view.WindowGeometry;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.document.SelectionSet;
import io.github.ajdailey.qcaret.ui.swt.platform.PlatformTraits;
import io.github.ajdailey.qcaret.ui.swt.search.ResultsDock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The editor menu's Format, Encode/Decode and Convert Case cascades and Toggle Line Comment, the
 * status bar's encoding and line-ending popups, and the results and bookmarks docks' context menus
 * in a real window: each opens on its control, a right click selects the row under the pointer, and
 * each item runs. Popups are hidden before the next drain, as a win32 popup would hold the test in
 * its loop.
 */
@Tag("swt")
class ContextMenusTest {
  private static final Duration BOUND = Duration.ofSeconds(30);

  @TempDir Path temp;

  private Display display;
  private Workers workers;
  private MainWindow window;
  private ApplicationController controller;
  private final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();

  @BeforeEach
  void open() throws Exception {
    Files.createDirectories(temp.resolve("conf"));
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
    window.place(new WindowGeometry.Request(null, null, 900, 600), null);
    window.open();
    drain();
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
        "open " + name);
    drain();
    return file;
  }

  private String text() {
    return controller.documents().activeEditor().document().text().toString();
  }

  /** Returns the item of a menu or its cascades whose label, keys left out, is a text. */
  private static MenuItem find(Menu menu, String label) {
    for (MenuItem item : menu.getItems()) {
      String text = item.getText();
      int tab = text.indexOf('\t');
      if ((tab < 0 ? text : text.substring(0, tab)).equals(label)) {
        return item;
      }
      if (item.getMenu() != null) {
        MenuItem inside = find(item.getMenu(), label);
        if (inside != null) {
          return inside;
        }
      }
    }
    return null;
  }

  private static MenuItem item(Menu menu, String label) {
    MenuItem item = find(menu, label);
    assertNotNull(item, label);
    return item;
  }

  private void select(MenuItem item) {
    if ((item.getStyle() & (SWT.RADIO | SWT.CHECK)) != 0) {
      item.setSelection(!item.getSelection() || (item.getStyle() & SWT.RADIO) != 0);
    }
    item.notifyListeners(SWT.Selection, new Event());
    drain();
  }

  private Menu editorMenu() {
    StyledText text = window.activeText();
    Point at = text.toDisplay(1, 1);
    Event event = new Event();
    event.x = at.x;
    event.y = at.y;
    event.detail = SWT.MENU_KEYBOARD;
    text.notifyListeners(SWT.MenuDetect, event);
    Menu menu = window.editorMenu().menu();
    menu.setVisible(false);
    return menu;
  }

  /**
   * Sends a pointer menu request at a table row, or below the rows for -1; returns the menu. The
   * point is the first one {@code Table.getItem(Point)} maps to the row: under GTK 4 a table with a
   * header reports {@code TableItem.getBounds} without the header's height, while {@code getItem}
   * and pointer events count it.
   */
  private static Menu rightClick(Table table, int row) {
    Rectangle area = table.getClientArea();
    Point at = table.toDisplay(area.x + 2, area.y + area.height - 2);
    if (row >= 0) {
      TableItem wanted = table.getItem(row);
      for (int y = 0; y < area.y + area.height; y++) {
        if (table.getItem(new Point(area.x + 2, y)) == wanted) {
          at = table.toDisplay(area.x + 2, y + 2);
          break;
        }
      }
    }
    Event event = new Event();
    event.x = at.x;
    event.y = at.y;
    event.detail = SWT.MENU_MOUSE;
    table.notifyListeners(SWT.MenuDetect, event);
    return table.getMenu();
  }

  private static void click(Control field) {
    Event event = new Event();
    event.button = 1;
    field.notifyListeners(SWT.MouseUp, event);
  }

  @Test
  void theEditorMenuRunsTheEditMenusCascadesAndToggleLineComment() throws Exception {
    openFile("data.json", "{\"a\":1}\n");
    Menu menu = editorMenu();
    for (UiText cascade :
        new UiText[] {UiText.MENU_FORMAT, UiText.MENU_ENCODE_DECODE, UiText.MENU_CONVERT_CASE}) {
      MenuItem holder = item(menu, cascade.text());
      assertSame(menu, holder.getParent());
      Menu inside = holder.getMenu();
      assertNotNull(inside, cascade.name());
      Menu bar = window.menuBar().item(CommandId.UPPER_CASE).getParent().getParentMenu();
      Menu edit = find(bar, cascade.text()).getMenu();
      assertEquals(edit.getItemCount(), inside.getItemCount(), cascade.name());
      for (int i = 0; i < edit.getItemCount(); i++) {
        assertEquals(edit.getItem(i).getData(), inside.getItem(i).getData(), cascade.name());
      }
    }
    assertSame(menu, item(menu, CommandId.TOGGLE_LINE_COMMENT.label()).getParent());
    assertSame(menu, item(menu, CommandId.TOGGLE_FOLD.label()).getParent());

    select(item(editorMenu(), CommandId.PRETTY_PRINT_JSON.label()));
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return text().startsWith("{\n");
          }
        },
        "Pretty Print JSON from the editor menu");
    controller.documents().activeEditor().selectFromView(0, 1);
    drain();
    select(item(editorMenu(), CommandId.BASE64_ENCODE.label()));
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return text().startsWith("ew==");
          }
        },
        "Base64 Encode from the editor menu");
  }

  @Test
  void convertCaseAndToggleLineCommentRunInTheView() throws Exception {
    controller.loadGrammars();
    openFile("case.java", "word\n");
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return window.statusText().endsWith("Java");
          }
        },
        "Java detected: " + window.statusText());
    controller.documents().activeEditor().selectFromView(0, 4);
    drain();
    select(item(editorMenu(), CommandId.UPPER_CASE.label()));
    assertEquals("WORD\n", text());
    controller.documents().activeEditor().selections().set(SelectionSet.caret(0));
    select(item(editorMenu(), CommandId.TOGGLE_LINE_COMMENT.label()));
    assertEquals("// WORD\n", text());
  }

  @Test
  void theLineEndingPopupChangesTheLineEnding() throws Exception {
    openFile("ending.txt", "one\ntwo\n");
    final Label[] fields = window.encodingFields();
    assertEquals(UiText.STATUS_LINE_ENDING_TIP.text(), fields[1].getToolTipText());
    click(fields[1]);
    Menu endings = window.statusMenus().lineEndingPopup();
    assertNotNull(endings);
    endings.setVisible(false);
    assertEquals(3, endings.getItemCount());
    assertEquals(CommandId.LINE_ENDING_LF, endings.getItem(0).getData());
    assertTrue(endings.getItem(0).getSelection());
    select(endings.getItem(1));
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return text().equals("one\r\ntwo\r\n")
                && fields[1].getText().equals(UiText.STATUS_CRLF.text());
          }
        },
        "CRLF from the status bar");
    click(fields[1]);
    Menu again = window.statusMenus().lineEndingPopup();
    again.setVisible(false);
    assertTrue(endings.isDisposed());
    assertTrue(again.getItem(1).getSelection());
  }

  @Test
  void theEncodingPopupReopensAndSavesWithAnEncoding() throws Exception {
    openFile("encoding.txt", "one\ntwo\n");
    final Label[] fields = window.encodingFields();
    assertEquals(UiText.STATUS_ENCODING_TIP.text(), fields[0].getToolTipText());
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return window.menuBar().otherEncodings(true).getEnabled();
          }
        },
        "charsets listed");
    click(fields[0]);
    Menu encodings = window.statusMenus().encodingPopup();
    assertNotNull(encodings);
    encodings.setVisible(false);
    assertEquals(UiText.MENU_REOPEN_ENCODING.text(), encodings.getItem(0).getText());
    assertEquals(UiText.MENU_SAVE_ENCODING.text(), encodings.getItem(1).getText());
    MenuItem other = item(encodings.getItem(0).getMenu(), UiText.MENU_ENCODING_OTHER.text());
    assertTrue(other.getEnabled());
    assertTrue(other.getMenu().getItemCount() > 0);
    select(item(encodings.getItem(0).getMenu(), "UTF-16BE"));
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return controller.documents().loading() == 0
                && fields[0].getText().contains("UTF-16BE");
          }
        },
        "reopened as UTF-16BE");

    click(fields[0]);
    assertSame(encodings, window.statusMenus().encodingPopup());
    encodings.setVisible(false);
    Menu save = encodings.getItem(1).getMenu();
    select(item(save, "UTF-16LE"));
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return window.menuBar().states().saveEncoding().equals("UTF-16LE");
          }
        },
        "saves as UTF-16LE");
    click(fields[0]);
    encodings.setVisible(false);
    assertTrue(item(save, "UTF-16LE").getSelection());
  }

  @Test
  void theResultsMenuSelectsTheRowAndRunsItsItems() throws Exception {
    Path tree = Files.createDirectories(temp.resolve("tree"));
    Path a = Files.writeString(tree.resolve("a b.txt"), "key one\nkey two\n");
    Files.writeString(tree.resolve("c.txt"), "key\n");
    final ResultsInput results = controller.search();
    controller
        .search()
        .run(
            new SearchForm(
                "key",
                false,
                true,
                false,
                SearchScope.TREE,
                tree.toString(),
                "",
                "",
                false,
                "",
                List.of(),
                List.of(),
                List.of()));
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return results.setCount() == 1 && !results.running(0);
          }
        },
        "search ends");
    drain();
    ResultsDock dock = window.resultsDock();
    Table table = dock.table();
    assertEquals(3, table.getItemCount());

    int file = -1;
    for (int f = 0; f < results.fileCount(0); f++) {
      if (results.file(0, f).text().startsWith("a b.txt")) {
        file = f;
      }
    }
    Menu menu = rightClick(table, dock.row(0, file));
    assertEquals(dock.row(0, file), table.getSelectionIndex());
    assertEquals(ResultsInput.MENU.size(), menu.getItemCount());
    assertTrue(item(menu, UiText.RESULTS_MENU_OPEN.text()).getEnabled());
    assertFalse(item(menu, UiText.RESULTS_MENU_PIN.text()).getSelection());
    select(item(menu, UiText.RESULTS_MENU_COPY.text()));
    assertTrue(
        window.clipboardText().endsWith(":2: key two")
            && window.clipboardText().contains("a b.txt:1: key one"),
        window.clipboardText());
    assertTrue(Files.isSameFile(a, Path.of(window.clipboardText().split(":1: ")[0])));

    select(item(rightClick(table, 0), UiText.RESULTS_MENU_EXPAND_ALL.text()));
    assertEquals(6, table.getItemCount());
    rightClick(table, dock.row(0, file, 1));
    assertEquals(dock.row(0, file, 1), table.getSelectionIndex());
    select(item(table.getMenu(), UiText.RESULTS_MENU_COPY.text()));
    assertTrue(window.clipboardText().endsWith("a b.txt:2: key two"), window.clipboardText());
    select(item(table.getMenu(), UiText.RESULTS_MENU_COLLAPSE_ALL.text()));
    assertEquals(1, table.getItemCount());
    assertEquals(0, table.getSelectionIndex());

    select(item(rightClick(table, 0), UiText.RESULTS_MENU_PIN.text()));
    assertTrue(results.pinned(0));
    assertTrue(item(rightClick(table, 0), UiText.RESULTS_MENU_PIN.text()).getSelection());
    select(item(table.getMenu(), UiText.RESULTS_MENU_REMOVE_SET.text()));
    assertEquals(0, results.setCount());
    assertEquals(0, table.getItemCount());
    Menu empty = rightClick(table, -1);
    assertFalse(item(empty, UiText.RESULTS_MENU_COPY_ALL.text()).getEnabled());
  }

  @Test
  void theBookmarksMenuSelectsTheRowAndCopiesClearsAndGoes() throws Exception {
    openFile("marks.txt", "zero\n  one\ttab\ntwo\n");
    for (int line = 1; line <= 2; line++) {
      controller
          .documents()
          .activeEditor()
          .selections()
          .set(
              SelectionSet.caret(
                  controller.documents().activeEditor().document().text().offsetOfLine(line)));
      controller.execute(CommandId.TOGGLE_BOOKMARK);
    }
    controller.execute(CommandId.BOOKMARKS_LIST);
    final BookmarksDock dock = window.bookmarksDock();
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return dock.table().getItemCount() == 2;
          }
        },
        "two rows");
    drain();
    Table table = dock.table();
    table.setSelection(1);
    Menu menu = rightClick(table, 0);
    assertEquals(0, table.getSelectionIndex());
    assertEquals(BookmarksInput.MENU.size(), menu.getItemCount());
    select(item(menu, UiText.BOOKMARKS_MENU_COPY_LINE.text()));
    assertEquals("  one\ttab", window.clipboardText());
    select(item(rightClick(table, 1), UiText.BOOKMARKS_MENU_GO_TO.text()));
    assertEquals(
        controller.documents().activeEditor().document().text().offsetOfLine(2),
        controller.documents().activeEditor().selections().selections().primary().caret());
    select(item(rightClick(table, 0), UiText.BOOKMARKS_MENU_CLEAR_ALL.text()));
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return dock.table().getItemCount() == 0;
          }
        },
        "cleared");
    assertFalse(item(rightClick(table, -1), UiText.BOOKMARKS_MENU_CLEAR_ALL.text()).getEnabled());
  }
}
