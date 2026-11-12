package io.github.ajdailey.qcaret.ui.swt;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.command.CommandGroup;
import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.command.Keymap;
import io.github.ajdailey.qcaret.app.config.ConfigStore;
import io.github.ajdailey.qcaret.app.config.Preferences;
import io.github.ajdailey.qcaret.app.config.SettingsLocation;
import io.github.ajdailey.qcaret.app.config.SettingsMode;
import io.github.ajdailey.qcaret.app.controller.ApplicationController;
import io.github.ajdailey.qcaret.app.controller.EditorController;
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
import io.github.ajdailey.qcaret.ui.swt.platform.PlatformTraits;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.dnd.DND;
import org.eclipse.swt.dnd.DropTarget;
import org.eclipse.swt.dnd.FileTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * File and document behavior in a real window: the drop target, the load row with Esc and Cancel,
 * the title, Mod+drag, the docks' sash, the Recently Closed and Automatic Detection entries and the
 * guards of the application menu's document event.
 */
@Tag("swt")
class DocumentBehaviorSurfaceTest {
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
            Keymap.defaults(PlatformTraits.PLATFORM.macKeys()),
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

  private BooleanSupplier tabs(final int count) {
    return new BooleanSupplier() {
      @Override
      public boolean getAsBoolean() {
        return window.tabs().getItemCount() == count && controller.documents().loading() == 0;
      }
    };
  }

  private Path file(String name, String text) throws Exception {
    return Files.writeString(temp.resolve(name), text, StandardCharsets.UTF_8);
  }

  private Path openFile(String name, String text) throws Exception {
    Path file = file(name, text);
    int count = window.tabs().getItemCount() + 1;
    controller.documents().open(List.of(file));
    await(tabs(count), "opened " + name);
    drain();
    return file;
  }

  private EditorController editor() {
    return controller.documents().activeEditor();
  }

  private static void mouse(StyledText text, int type, int x, int y, int stateMask) {
    Event event = new Event();
    event.button = type == SWT.MouseMove ? 0 : 1;
    event.count = 1;
    event.x = x;
    event.y = y;
    event.stateMask = stateMask;
    text.notifyListeners(type, event);
  }

  private Point at(StyledText text, int offset) {
    Point point = text.getLocationAtOffset(editor().projection().toDisplay(offset));
    return new Point(point.x + 1, point.y + 2);
  }

  @Test
  void theShellTakesFileDropsAndNothingElse() {
    DropTarget target =
        assertInstanceOf(DropTarget.class, window.shell().getData(DND.DROP_TARGET_KEY));
    assertArrayEquals(new Transfer[] {FileTransfer.getInstance()}, target.getTransfer());
  }

  @Test
  void droppedFilesOpenATabEachOverAnOpenEditor() throws Exception {
    openFile("open.txt", "open\n");
    Path a = file("a.txt", "a\n");
    Path b = file("b.txt", "b\n");
    window.dropped(new String[] {a.toString(), b.toString()});
    await(tabs(3), "two tabs");
    // The loads run on workers: the tabs take the order they finish in.
    Set<String> names =
        Set.of(window.tabs().getItem(1).getText(), window.tabs().getItem(2).getText());
    assertEquals(Set.of("a.txt", "b.txt"), names);
  }

  @Test
  void aDroppedNameThatDoesNotParseStillOpensTheRest() throws Exception {
    Path a = file("a.txt", "a\n");
    window.dropped(new String[] {"bad\0name", a.toString()});
    await(tabs(1), "the valid file");
  }

  @Test
  void theLoadRowShowsAboveTheStatusBarAndHides() {
    ProgressRow bar = window.loadBar();
    assertFalse(bar.shown());
    window.showLoadProgress("Loading a&b", 40, new AtomicInteger()::incrementAndGet);
    drain();
    assertTrue(bar.shown());
    assertEquals("Loading a&&b", bar.text());
    assertEquals(40, bar.percent());
    assertTrue(bar.control().getSize().y > 0);
    window.hideLoadProgress();
    drain();
    assertFalse(bar.shown());
  }

  @Test
  void escAndCancelRunTheCancelAndEscGoesNoFurther() throws Exception {
    openFile("a.txt", "alpha beta\n");
    editor().selections().set(SelectionSet.of(List.of(Selection.caret(1), Selection.caret(3)), 0));
    final AtomicInteger canceled = new AtomicInteger();
    window.showLoadProgress(
        "Loading",
        10,
        new Runnable() {
          @Override
          public void run() {
            canceled.incrementAndGet();
          }
        });
    Event escape = new Event();
    escape.keyCode = SWT.ESC;
    window.activeText().notifyListeners(SWT.KeyDown, escape);
    assertEquals(1, canceled.get());
    assertEquals(2, editor().selections().selections().size());
    window.loadBar().cancelButton().notifyListeners(SWT.Selection, new Event());
    assertEquals(2, canceled.get());
    window.hideLoadProgress();
    window.activeText().notifyListeners(SWT.KeyDown, escape);
    assertEquals(2, canceled.get());
    assertEquals(1, editor().selections().selections().size());
  }

  @Test
  void theTitleFollowsTheSelectedDocumentAndItsDirtyState() throws Exception {
    assertEquals(UiText.WINDOW_TITLE.text(), window.shell().getText());
    openFile("a.txt", "alpha\n");
    final String clean =
        UiText.WINDOW_TITLE_FILE.format("a.txt", controller.documents().activePath());
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return clean.equals(window.shell().getText());
          }
        },
        "the clean title");
    editor().type("x");
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return window.shell().getText().startsWith("*a.txt");
          }
        },
        "the dirty mark");
    controller.documents().newDocument();
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !window.shell().getText().contains("a.txt");
          }
        },
        "the new document's title");
  }

  @Test
  void modDragAddsASelectionBesideTheOnesBeforeThePress() throws Exception {
    openFile("a.txt", "alpha beta gamma\n");
    StyledText text = window.activeText();
    editor().selections().set(SelectionSet.of(List.of(Selection.of(0, 2)), 0));
    Point from = at(text, 6);
    Point to = at(text, 10);
    mouse(text, SWT.MouseDown, from.x, from.y, SWT.MOD1);
    assertEquals(2, editor().selections().selections().size());
    mouse(text, SWT.MouseMove, to.x, to.y, SWT.MOD1 | SWT.BUTTON1);
    mouse(text, SWT.MouseUp, to.x, to.y, SWT.MOD1 | SWT.BUTTON1);
    assertEquals(
        List.of(Selection.of(0, 2), Selection.of(6, 10)),
        editor().selections().selections().selections());
    assertEquals(Selection.of(6, 10), editor().selections().selections().primary());
  }

  @Test
  void aModClickThatRemovesACaretStaysRemovedOnASmallMove() throws Exception {
    openFile("a.txt", "alpha beta gamma\n");
    StyledText text = window.activeText();
    editor().selections().set(SelectionSet.of(List.of(Selection.caret(1), Selection.caret(6)), 0));
    Point press = at(text, 6);
    mouse(text, SWT.MouseDown, press.x, press.y, SWT.MOD1);
    mouse(text, SWT.MouseMove, press.x + 1, press.y, SWT.MOD1 | SWT.BUTTON1);
    mouse(text, SWT.MouseUp, press.x + 1, press.y, SWT.MOD1 | SWT.BUTTON1);
    assertEquals(List.of(Selection.caret(1)), editor().selections().selections().selections());
  }

  @Test
  void theDocksSashRoundTrips() {
    window.setResultsWeights(new int[] {1, 4});
    int[] weights = window.resultsWeights();
    assertEquals(2, weights.length);
    assertTrue(weights[1] > 3 * weights[0], weights[0] + "," + weights[1]);
  }

  @Test
  void recentlyClosedListsTheFilesAboveItsClearItem() throws Exception {
    openFile("a.txt", "a\n");
    openFile("b.txt", "b\n");
    controller.execute(CommandId.CLOSE_ALL);
    drain();
    window.menuBar().sync(CommandGroup.FILE);
    Menu cascade = window.menuBar().item(CommandId.CLEAR_CLOSED_FILES).getParent();
    MenuItem[] items = cascade.getItems();
    assertTrue(items[0].getText().contains("b.txt"), items[0].getText());
    assertTrue(items[1].getText().contains("a.txt"), items[1].getText());
    items[1].notifyListeners(SWT.Selection, new Event());
    await(tabs(2), "the chosen file");
    assertEquals("a.txt", window.tabs().getItem(1).getText());
  }

  @Test
  void aMiddleClickOnTheLastTabLeavesASelectedUntitledTab() throws Exception {
    openFile("a.txt", "a\n");
    Rectangle bounds = window.tabs().getItem(0).getBounds();
    Event click = new Event();
    click.button = 2;
    click.x = bounds.x + bounds.width / 2;
    click.y = bounds.y + bounds.height / 2;
    window.tabs().notifyListeners(SWT.MouseUp, click);
    drain();
    assertEquals(1, window.tabs().getItemCount());
    assertEquals(UiText.TAB_UNTITLED.format(1), window.tabs().getItem(0).getText());
    assertEquals(window.tabs().getItem(0), window.tabs().getSelection());
    controller.execute(CommandId.REOPEN_CLOSED);
    await(tabs(2), "the reopened file");
  }

  @Test
  void recentlyClosedOpenAllSitsAboveClearAndOpensEveryFile() throws Exception {
    openFile("a.txt", "a\n");
    openFile("b.txt", "b\n");
    controller.execute(CommandId.CLOSE_ALL);
    drain();
    window.menuBar().sync(CommandGroup.FILE);
    MenuItem openAll = window.menuBar().item(CommandId.OPEN_ALL_CLOSED);
    MenuItem[] items = openAll.getParent().getItems();
    assertEquals(5, items.length);
    assertEquals(openAll, items[3]);
    assertEquals(window.menuBar().item(CommandId.CLEAR_CLOSED_FILES), items[4]);
    assertTrue(openAll.getEnabled());
    openAll.notifyListeners(SWT.Selection, new Event());
    await(tabs(3), "every closed file");
  }

  @Test
  void automaticDetectionLeadsReopenWithEncoding() throws Exception {
    Path file = openFile("a.txt", "café\n");
    controller.reopenWithEncoding(StandardCharsets.ISO_8859_1);
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return window.activeText() != null && window.activeText().getText().startsWith("cafÃ");
          }
        },
        "the forced charset");
    MenuItem holder = window.menuBar().otherEncodings(true).getParent().getParentItem();
    MenuItem automatic = holder.getMenu().getItem(0);
    assertEquals(UiText.MENU_ENCODING_AUTO.text(), automatic.getText());
    automatic.notifyListeners(SWT.Selection, new Event());
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return window.activeText() != null && window.activeText().getText().startsWith("café");
          }
        },
        "detection " + file.getFileName());
  }

  @Test
  void theTabWidthPromptAnswersItsSpinner() throws Exception {
    openFile("a.txt", "\ta\n");
    final AtomicInteger answer = new AtomicInteger();
    TabWidthDialog dialog = new TabWidthDialog(window.shell(), 4, 16, answer::set);
    dialog.open();
    dialog.field().setSelection(16);
    dialog.shell().getDefaultButton().notifyListeners(SWT.Selection, new Event());
    assertEquals(16, answer.get());
    assertTrue(dialog.shell().isDisposed());
  }

  @Test
  void theReloadPromptDeclinesOnClose() throws Exception {
    final AtomicInteger declined = new AtomicInteger();
    ReloadDialog dialog =
        new ReloadDialog(
            window.shell(),
            "a.txt",
            new Runnable() {
              @Override
              public void run() {
                declined.addAndGet(100);
              }
            },
            declined::incrementAndGet);
    dialog.open();
    dialog.shell().close();
    drain();
    assertEquals(1, declined.get());
  }

  @Test
  void theApplicationMenuIgnoresADocumentNameItCannotOpen() throws Exception {
    AppMenu.open(null, null);
    AppMenu.open(null, "");
    AppMenu.open(null, "bad\0name");
    Path a = file("a.txt", "a\n");
    AppMenu.open(controller.windowSet(), a.toString());
    await(tabs(1), "the named file");
  }
}
