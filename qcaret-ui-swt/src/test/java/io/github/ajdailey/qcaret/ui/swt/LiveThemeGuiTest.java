package io.github.ajdailey.qcaret.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
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
import io.github.ajdailey.qcaret.app.controller.EditorController;
import io.github.ajdailey.qcaret.app.controller.SearchController;
import io.github.ajdailey.qcaret.app.controller.WindowSet;
import io.github.ajdailey.qcaret.app.theme.EditorSchemes;
import io.github.ajdailey.qcaret.app.theme.Palette;
import io.github.ajdailey.qcaret.app.theme.ThemeMode;
import io.github.ajdailey.qcaret.app.view.FindState;
import io.github.ajdailey.qcaret.app.view.HexMode;
import io.github.ajdailey.qcaret.app.view.MarkdownViewMode;
import io.github.ajdailey.qcaret.app.view.SearchForm;
import io.github.ajdailey.qcaret.app.view.SearchScope;
import io.github.ajdailey.qcaret.app.view.WindowGeometry;
import io.github.ajdailey.qcaret.app.view.WorkspaceNode;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.document.Document;
import io.github.ajdailey.qcaret.core.document.SelectionSet;
import io.github.ajdailey.qcaret.ui.swt.platform.PlatformTraits;
import io.github.ajdailey.qcaret.ui.swt.search.ResultsDock;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Cursor;
import org.eclipse.swt.graphics.DeviceData;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.ToolItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A live theme switch that rebuilds the main windows, forced on every platform through the win32
 * theme following: the state each window carries, the deferral behind a dialog, and flat counts
 * over repeated switches.
 */
@Tag("swt")
class LiveThemeGuiTest {
  private static final Duration BOUND = Duration.ofSeconds(30);

  private static final boolean GTK = "gtk".equals(SWT.getPlatform());

  @TempDir Path temp;

  private final List<Throwable> failures = new CopyOnWriteArrayList<Throwable>();
  private final List<MainWindowSlot> slots = new ArrayList<MainWindowSlot>();
  private Display display;
  private Workers workers;
  private WindowSet set;
  private WindowViews views;
  private boolean dark;
  private Menu shownMenu;

  @BeforeEach
  void open() {
    DeviceData data = new DeviceData();
    data.tracking = true;
    display = new Display(data);
    workers =
        new Workers(
            2,
            new Thread.UncaughtExceptionHandler() {
              @Override
              public void uncaughtException(Thread thread, Throwable error) {
                failures.add(error);
              }
            });
    SwtThemeService theme =
        SwtThemeService.open(
            display, ThemeMode.LIGHT, PlatformTraits.of("win32", false).themeFollow(display));
    set =
        new WindowSet(
            workers,
            new SwtUiDispatcher(display),
            new ConfigStore(new SettingsLocation(SettingsMode.EXPLICIT, temp.resolve("conf"))),
            Preferences.DEFAULTS.withTheme(ThemeMode.LIGHT),
            theme,
            new MemoryBudget(Runtime.getRuntime().maxMemory(), 60),
            Keymap.defaults(PlatformTraits.PLATFORM.macKeys()),
            false);
    views =
        new WindowViews(
            display,
            new WindowViews.Factory() {
              @Override
              public MainWindow create(ApplicationController controller) {
                MainWindow window = new MainWindow(display, controller.theme().palette());
                window.attach(controller);
                return window;
              }
            },
            // The menu test sends SWT.Show and SWT.Hide to a menu that is never mapped.
            new ShownMenus(
                display,
                new Predicate<Menu>() {
                  @Override
                  public boolean test(Menu menu) {
                    return menu == shownMenu || menu.getVisible();
                  }
                }));
    theme.setWindowRebuild(views);
    set.setFactory(
        new WindowSet.Factory() {
          @Override
          public ApplicationController create(
              WindowSet owner, WindowGeometry.Request at, boolean maximized) {
            return window(owner, at);
          }
        });
  }

  private ApplicationController window(WindowSet owner, WindowGeometry.Request at) {
    MainWindow window = new MainWindow(display, Palette.LIGHT);
    MainWindowSlot slot = new MainWindowSlot(window);
    slots.add(slot);
    ApplicationController created = new ApplicationController(owner, slot);
    window.attach(created);
    views.add(slot, created);
    Integer x = at.x() == null ? Integer.valueOf(40 + 60 * (slots.size() - 1)) : at.x();
    window.place(new WindowGeometry.Request(x, 30, 760, 520), null);
    window.open();
    return created;
  }

  @AfterEach
  void close() {
    set.quit();
    runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            for (MainWindowSlot slot : slots) {
              if (!slot.window().shell().isDisposed()) {
                return false;
              }
            }
            return true;
          }
        });
    display.setDarkThemePreferred(false);
    display.dispose();
    workers.close();
    assertTrue(failures.isEmpty(), failures.toString());
  }

  private boolean runUntil(BooleanSupplier condition) {
    return EventLoop.runUntil(display, condition, BOUND);
  }

  private ApplicationController first() {
    return window(set, new WindowGeometry.Request(null, null, null, null));
  }

  private void open(final ApplicationController controller, String... names) throws Exception {
    final int expected = controller.documents().count() + names.length;
    List<Path> files = new ArrayList<Path>();
    for (String name : names) {
      StringBuilder text = new StringBuilder();
      for (int i = 1; i <= 300; i++) {
        text.append(name).append(" line ").append(i).append('\n');
      }
      files.add(Files.writeString(temp.resolve(name), text.toString(), StandardCharsets.UTF_8));
    }
    controller.documents().open(files);
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return controller.documents().count() == expected
                    && controller.documents().loading() == 0;
              }
            }),
        "files opened");
  }

  /** Switches the theme through the settings and waits until no window waits for its rebuild. */
  private void toggle() {
    dark = !dark;
    final int before = views.rebuilds();
    set.settings().set(set.settings().current().withTheme(dark ? ThemeMode.DARK : ThemeMode.LIGHT));
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return views.rebuilds() > before && !views.pending();
              }
            }),
        "windows rebuilt");
  }

  private void settle() {
    final long end = System.nanoTime() + 300_000_000L;
    runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return System.nanoTime() > end;
          }
        });
  }

  private static List<String> titles(MainWindow window) {
    List<String> list = new ArrayList<String>();
    for (int i = 0; i < window.tabs().getItemCount(); i++) {
      list.add(window.tabs().getItem(i).getText());
    }
    return list;
  }

  @Test
  void aSwitchRebuildsTheWindowAndCarriesItsState() throws Exception {
    ApplicationController controller = first();
    open(controller, "one.txt", "two.txt", "three.txt");
    controller.documents().select(1);
    EditorController editor = controller.documents().activeEditor();
    Document document = editor.document();
    editor.selections().set(SelectionSet.caret(40));
    editor.type("XYZ");
    editor.selectFromView(30, 60);
    editor.view().scrollToRow(120);
    controller.documents().split(true);
    EditorController focused = controller.documents().activeEditor();
    controller.find().open(true);
    controller.find().setText("line 7");
    controller.find().setReplacement("LINE");
    controller.execute(CommandId.WORKSPACE_BROWSER);
    controller.execute(CommandId.BOOKMARKS_LIST);
    settle();
    // FindState.running is true while the search for the text runs on a worker.
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return !controller.find().state().running();
              }
            }),
        "search done");
    MainWindowSlot slot = slots.get(0);
    MainWindow old = slot.window();
    List<String> titles = titles(old);
    Rectangle bounds = old.shell().getBounds();
    int topRow = editor.view().topRow();
    FindState find = controller.find().state();
    slot.setActive();

    toggle();

    MainWindow now = slot.window();
    assertNotSame(old, now);
    assertTrue(old.shell().isDisposed());
    assertTrue(SwtThemeService.nativeDark(display));
    assertEquals(titles, titles(now));
    assertEquals(1, now.tabs().getSelectionIndex());
    assertSame(document, controller.documents().activeEditor().document());
    assertSame(focused, controller.documents().activeEditor());
    assertEquals(2, now.activeEditors().size());
    assertTrue(document.isDirty());
    assertEquals(30, editor.selections().selections().primary().start());
    assertEquals(60, editor.selections().selections().primary().end());
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return editor.view().topRow() == topRow;
              }
            }),
        "top row " + topRow + " -> " + editor.view().topRow());
    assertEquals(find, controller.find().state());
    assertTrue(now.findBar().control().getVisible());
    assertTrue(now.workspaceShown());
    assertTrue(now.bookmarksShown());
    assertEquals(bounds, now.shell().getBounds());
    assertEquals(1, slot.busy().active());
    assertNotNull(now.shell().getCursor());
    slot.setIdle();
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return now.shell().getCursor() == null;
              }
            }),
        "busy cursor cleared");
    editor.execute(CommandId.UNDO);
    assertFalse(document.text().toString().contains("XYZ"));
  }

  @Test
  void aSwitchKeepsTheResultsTreesExpandedGroupsAndSelectedRow() throws Exception {
    final ApplicationController controller = first();
    open(controller, "one.txt", "two.txt");
    final SearchController search = controller.search();
    search.run(
        new SearchForm(
            "line 7",
            false,
            true,
            false,
            SearchScope.OPEN_DOCUMENTS,
            "",
            "",
            "",
            false,
            "",
            List.of(),
            List.of(),
            List.of()));
    MainWindowSlot slot = slots.get(0);
    final ResultsDock old = slot.window().resultsDock();
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return search.setCount() == 1
                    && !search.running(0)
                    && search.fileCount(0) == 2
                    && old.row(0, 1) >= 0;
              }
            }),
        "search ends");
    old.activate(old.row(0, 1));
    int match = old.row(0, 1, 2);
    old.table().setSelection(match);
    old.table().notifyListeners(SWT.Selection, new Event());
    assertTrue(old.text(old.row(0, 1)).startsWith("    - "));

    toggle();

    ResultsDock now = slot.window().resultsDock();
    assertNotSame(old, now);
    assertTrue(now.text(now.row(0, 0)).startsWith("    + "));
    assertTrue(now.text(now.row(0, 1)).startsWith("    - "));
    assertEquals(match, now.row(0, 1, 2));
    assertEquals(match, now.table().getSelectionIndex());
  }

  @Test
  void eachTurnStagesOneTabInAHiddenWindowWhileTheOldOneTakesInput() throws Exception {
    final ApplicationController controller = first();
    open(controller, "a.txt", "b.txt", "c.txt");
    MainWindowSlot slot = slots.get(0);
    MainWindow old = slot.window();

    dark = true;
    set.settings().set(set.settings().current().withTheme(ThemeMode.DARK));
    int staged = 0;
    boolean typed = false;
    List<String> during = null;
    int selected = -1;
    Document typedInto = null;
    long end = System.nanoTime() + BOUND.toNanos();
    while (slot.window() == old && System.nanoTime() < end) {
      display.readAndDispatch();
      MainWindow next = views.building(slot);
      if (next != null && slot.window() == old) {
        assertFalse(next.shell().getVisible(), "the new window stays hidden");
        assertTrue(old.shell().getVisible());
        int count = next.tabs().getItemCount();
        assertTrue(count - staged <= 1, "one tab per turn: " + staged + " -> " + count);
        staged = count;
        if (count == 1 && !typed) {
          typed = true;
          controller.documents().select(0);
          typedInto = controller.documents().activeEditor().document();
          controller.documents().activeEditor().type("Q");
          assertEquals(firstLine(typedInto), old.activeEditors().get(0).text().getLine(0));
          typeAtEnd(controller.documents().activeEditor(), "staging\n");
          controller.documents().newDocument();
          assertEquals(4, old.tabs().getItemCount());
          during = titles(old);
          selected = old.tabs().getSelectionIndex();
        }
      }
    }
    assertTrue(typed, "input during the rebuild");
    assertNotSame(old, slot.window());
    assertFalse(old.shell().getVisible());
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return old.shell().isDisposed();
              }
            }),
        "old window disposed on the next turn");
    assertEquals(during, titles(slot.window()));
    assertEquals(selected, slot.window().tabs().getSelectionIndex());
    controller.documents().select(0);
    assertSame(typedInto, controller.documents().activeEditor().document());
    assertTrue(firstLine(typedInto).startsWith("Q"));
    assertEquals(firstLine(typedInto), slot.window().activeEditors().get(0).text().getLine(0));
    typeAtEnd(controller.documents().activeEditor(), "swapped\n");
    assertTrue(typedInto.text().toString().endsWith("staging\nswapped\n"));
  }

  /** Types past the end of the text a pane was created with, so a pane not following it fails. */
  private static void typeAtEnd(EditorController editor, String text) {
    editor.selections().set(SelectionSet.caret(editor.document().text().length()));
    editor.type(text);
  }

  private static String firstLine(Document document) {
    String text = document.text().toString();
    return text.substring(0, text.indexOf('\n'));
  }

  @Test
  void aSwitchBackDuringARebuildDisposesTheHiddenWindow() throws Exception {
    ApplicationController controller = first();
    open(controller, "a.txt", "b.txt", "c.txt");
    final MainWindowSlot slot = slots.get(0);
    MainWindow old = slot.window();
    set.settings().set(set.settings().current().withTheme(ThemeMode.DARK));
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                MainWindow next = views.building(slot);
                return next != null && next.tabs().getItemCount() == 1;
              }
            }),
        "a tab staged");
    final MainWindow next = views.building(slot);

    set.settings().set(set.settings().current().withTheme(ThemeMode.LIGHT));
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return next.shell().isDisposed();
              }
            }),
        "the hidden window disposed");
    settle();

    assertNull(views.building(slot));
    assertSame(old, slot.window());
    assertFalse(views.pending());
    assertEquals(0, views.rebuilds());
    assertEquals(3, old.tabs().getItemCount());
    controller.documents().activeEditor().type("Q");
    Document typed = controller.documents().activeEditor().document();
    assertTrue(firstLine(typed).startsWith("Q"));
    assertEquals(firstLine(typed), old.activeEditors().get(0).text().getLine(0));
  }

  @Test
  void theMarkdownSwitchShowsSplitAfterARebuild() throws Exception {
    final ApplicationController controller = first();
    controller.loadGrammars();
    Files.writeString(temp.resolve("notes.md"), "# Title\n\n## Section\n\ntext\n");
    controller.documents().open(List.of(temp.resolve("notes.md")));
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return controller.documents().activeMarkdown();
              }
            }),
        "Markdown tab");
    assertTrue(controller.documents().setMarkdownMode(MarkdownViewMode.SPLIT));
    settle();

    toggle();
    settle();

    assertEquals(MarkdownViewMode.SPLIT, controller.documents().markdownMode());
    ToolItem[] modes = slots.get(0).window().markdownSwitch().items();
    assertFalse(modes[0].isDisposed());
    assertEquals(CommandId.MARKDOWN_SPLIT, modes[1].getData());
    assertFalse(modes[0].getSelection(), "Source");
    assertTrue(modes[1].getSelection(), "Split");
    assertNotNull(slots.get(0).window().activePreview());
  }

  /** The tab's hex pane and a Hex Viewer window are created again in the new window. */
  @Test
  void aHexPaneAndAHexViewerShowAgainAfterARebuild() throws Exception {
    final ApplicationController controller = first();
    open(controller, "bytes.txt");
    final long size = Files.size(temp.resolve("bytes.txt"));
    assertTrue(controller.documents().setHexMode(HexMode.SPLIT));
    controller
        .workspace()
        .openInHexViewer(
            new WorkspaceNode(temp.resolve("bytes.txt"), "bytes.txt", WorkspaceNode.Kind.FILE));
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                WindowHexViews hex = slots.get(0).window().hexViews();
                return hex.windows().size() == 1
                    && hex.panes().size() == 1
                    && hex.panes().get(0).rows().size() == size;
              }
            }),
        "pane and viewer");

    toggle();
    settle();

    final WindowHexViews hex = slots.get(0).window().hexViews();
    assertEquals(HexMode.SPLIT, controller.documents().hexMode());
    assertEquals(1, hex.panes().size());
    assertTrue(hex.panes().get(0).control().getVisible());
    assertEquals(size, hex.panes().get(0).rows().size());
    assertEquals(1, hex.windows().size());
    assertEquals(size, hex.windows().get(0).pane().rows().size());
  }

  @Test
  void aModalPromptDefersOnlyItsOwnWindow() throws Exception {
    ApplicationController controller = first();
    open(controller, "prompt.txt");
    first();
    MainWindow waiting = slots.get(0).window();
    MainWindow other = slots.get(1).window();
    controller.execute(CommandId.GO_TO_LINE);
    assertTrue(waiting.promptShown());

    dark = true;
    set.settings().set(set.settings().current().withTheme(ThemeMode.DARK));
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return slots.get(1).window() != other;
              }
            }),
        "the other window rebuilt");
    settle();
    assertSame(waiting, slots.get(0).window());
    assertTrue(views.pending());

    for (Shell child : waiting.shell().getShells()) {
      child.close();
    }
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return !views.pending();
              }
            }),
        "the waiting window rebuilt");
    assertNotSame(waiting, slots.get(0).window());
    assertEquals(1, slots.get(0).window().tabs().getItemCount());
  }

  @Test
  void preferencesDefersItsWindowAndSwitchingBackLeavesItAlone() throws Exception {
    ApplicationController controller = first();
    MainWindow waiting = slots.get(0).window();
    controller.execute(CommandId.PREFERENCES);
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return waiting.preferencesDialog() != null;
              }
            }),
        "Preferences open");

    set.settings().set(set.settings().current().withTheme(ThemeMode.DARK));
    settle();
    assertTrue(views.pending());
    set.settings().set(set.settings().current().withTheme(ThemeMode.LIGHT));
    settle();
    assertFalse(views.pending());
    waiting.preferencesDialog().shell().close();
    settle();

    assertSame(waiting, slots.get(0).window());
    assertEquals(0, views.rebuilds());
  }

  @Test
  void aShownMenuDefersTheCreateAndTheSwap() throws Exception {
    ApplicationController controller = first();
    open(controller, "menu.txt");
    final MainWindowSlot slot = slots.get(0);
    MainWindow old = slot.window();
    Menu menu = new Menu(old.shell(), SWT.POP_UP);
    shownMenu = menu;
    try {
      menu.notifyListeners(SWT.Show, new Event());
      set.settings().set(set.settings().current().withTheme(ThemeMode.DARK));
      settle();
      assertNull(views.building(slot), "no window created while the menu shows");
      assertTrue(views.pending());

      menu.notifyListeners(SWT.Hide, new Event());
      assertTrue(
          runUntil(
              new BooleanSupplier() {
                @Override
                public boolean getAsBoolean() {
                  MainWindow next = views.building(slot);
                  return next != null && next.tabs().getItemCount() == 1;
                }
              }),
          "the tab staged");
      menu.notifyListeners(SWT.Show, new Event());
      settle();
      assertSame(old, slot.window(), "no swap while the menu shows");

      menu.notifyListeners(SWT.Hide, new Event());
      assertTrue(
          runUntil(
              new BooleanSupplier() {
                @Override
                public boolean getAsBoolean() {
                  return !views.pending();
                }
              }),
          "rebuilt after the menu closed");
      assertNotSame(old, slot.window());
    } finally {
      menu.dispose();
    }
  }

  @Test
  void aNativeDialogOverAnOwnedWindowDefersTheSwap() throws Exception {
    first();
    MainWindowSlot slot = slots.get(0);
    MainWindow old = slot.window();
    Shell owned = new Shell(old.shell());
    Shell top = NativeDialogs.enter(owned);
    assertSame(old.shell(), top);
    assertTrue(slot.holdsDialog());

    set.settings().set(set.settings().current().withTheme(ThemeMode.DARK));
    settle();
    assertSame(old, slot.window());
    assertTrue(views.pending());

    NativeDialogs.leave(top);
    assertFalse(slot.holdsDialog());
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return !views.pending();
              }
            }),
        "rebuilt after the dialog returned");
    assertNotSame(old, slot.window());
  }

  @Test
  void aHoldDefersTheRebuildOnlyWhileItHolds() throws Exception {
    first();
    MainWindowSlot slot = slots.get(0);
    MainWindow old = slot.window();
    Shell owned = new Shell(old.shell());
    owned.setSize(200, 100);
    owned.open();
    final boolean[] holding = {true};
    WindowViews.holdWhile(
        owned,
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return holding[0];
          }
        });

    set.settings().set(set.settings().current().withTheme(ThemeMode.DARK));
    settle();
    assertNull(views.building(slot));
    assertTrue(views.pending());

    holding[0] = false;
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return !views.pending();
              }
            }),
        "rebuilt once the hold ended");
    assertNotSame(old, slot.window());
  }

  @Test
  void aHiddenTabTakesAPaletteWhenItIsSelected() throws Exception {
    ApplicationController controller = first();
    open(controller, "shown.txt", "hidden.txt");
    controller.documents().select(0);
    MainWindow window = slots.get(0).window();
    EditorTab hidden = (EditorTab) window.tabs().getItem(1).getData();
    EditorTab shown = (EditorTab) window.tabs().getItem(0).getData();
    Color before = hidden.panes.get(0).text().getBackground();

    controller.theme().setEditorColors(EditorSchemes.MIDNIGHT);
    Color after = shown.panes.get(0).text().getBackground();
    assertNotEquals(before.getRGB(), after.getRGB());
    assertEquals(before.getRGB(), hidden.panes.get(0).text().getBackground().getRGB());

    controller.documents().select(1);
    assertEquals(after.getRGB(), hidden.panes.get(0).text().getBackground().getRGB());
  }

  @Test
  void countsStayFlatOverRepeatedSwitches() throws Exception {
    ApplicationController controller = first();
    open(controller, "a.txt", "b.txt", "c.txt");
    controller.documents().split(false);
    toggle();
    toggle();
    settle();
    System.gc();
    Map<String, Integer> before = counts();
    for (int i = 0; i < 10; i++) {
      toggle();
    }
    settle();
    System.gc();
    Map<String, Integer> after = counts();
    assertEquals(before, after);
    assertEquals(12, views.rebuilds());
  }

  /**
   * The old window's selected tab keeps its editor colors until the swap disposes it: each {@code
   * StyledText} color change costs O(lines), which held a 176 MB file's rebuild for seconds.
   */
  @Test
  void theReplacedWindowsSelectedTabKeepsItsColorsUntilTheSwap() throws Exception {
    ApplicationController controller = first();
    open(controller, "shown.txt");
    MainWindowSlot slot = slots.get(0);
    final MainWindow old = slot.window();
    int selected = old.tabs().getSelectionIndex();
    RGB before = selection(old, selected);

    set.settings().set(set.settings().current().withTheme(ThemeMode.DARK));
    assertTrue(WindowViews.replacing(old.shell()));
    assertEquals(before, selection(old, selected));
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return !views.pending() && old.shell().isDisposed();
              }
            }),
        "rebuilt");
    assertNotEquals(before, selection(slot.window(), selected));
  }

  @Test
  void aWindowThatWaitsTakesTheNewColorsAtOnce() throws Exception {
    ApplicationController controller = first();
    open(controller, "shown.txt");
    MainWindow old = slots.get(0).window();
    int selected = old.tabs().getSelectionIndex();
    RGB before = selection(old, selected);
    Shell owned = new Shell(old.shell());
    owned.setSize(200, 100);
    owned.open();
    WindowViews.holdWhile(owned, WindowViews.WHILE_OPEN);

    set.settings().set(set.settings().current().withTheme(ThemeMode.DARK));
    final Shell shell = old.shell();
    // GTK 4 applies the theme change over several turns; the hold releases the window after it.
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return !WindowViews.replacing(shell);
              }
            }),
        "released");
    assertTrue(views.pending());
    assertNotEquals(before, selection(old, selected));
    owned.dispose();
  }

  /**
   * Returns the selected text background of a tab's first editor. Not the text background: the
   * light palette's white equals GTK's default, so {@code EditorPane} never sets it, and a native
   * theme change on GTK 4 changes what the widget reports.
   */
  private static RGB selection(MainWindow window, int index) {
    EditorTab tab = (EditorTab) window.tabs().getItem(index).getData();
    return tab.panes.get(0).text().getSelectionBackground().getRGB();
  }

  private Map<String, Integer> counts() throws Exception {
    Map<String, Integer> map = new TreeMap<String, Integer>();
    int shells = 0;
    for (Shell shell : display.getShells()) {
      if (!shell.isDisposed()) {
        shells++;
      }
    }
    map.put("shells", shells);
    for (Object resource : display.getDeviceData().objects) {
      // GTK SWT moves its cached system cursors to Display.resources at each GTK theme change
      // (saveResources) and disposes them with the Display.
      if (resource != null && !(GTK && resource instanceof Cursor)) {
        String key = "resource " + resource.getClass().getSimpleName();
        map.put(key, map.getOrDefault(key, 0) + 1);
      }
    }
    map.put("settings listeners", size(set.settings(), "listeners"));
    map.put("palette listeners", size(set.theme(), "listeners"));
    ApplicationController controller = set.windows().get(0);
    for (int i = 0; i < controller.documents().count(); i++) {
      controller.documents().select(i);
      Document document = controller.documents().activeEditor().document();
      map.put("document " + i + " listeners", size(document, "listeners"));
      map.put("document " + i + " selection models", size(document, "models"));
    }
    return map;
  }

  private static int size(Object owner, String name) throws Exception {
    Class<?> type = owner.getClass();
    while (type != null) {
      try {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        Object value = field.get(owner);
        return value instanceof Collection<?> c ? c.size() : -1;
      } catch (NoSuchFieldException e) {
        type = type.getSuperclass();
      }
    }
    throw new NoSuchFieldException(name);
  }
}
