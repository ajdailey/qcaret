package io.github.ajdailey.qcaret.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import io.github.ajdailey.qcaret.app.theme.Palette;
import io.github.ajdailey.qcaret.app.theme.ThemeMode;
import io.github.ajdailey.qcaret.app.view.ResultsInput;
import io.github.ajdailey.qcaret.app.view.WindowGeometry;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.ui.swt.editor.EditorPane;
import io.github.ajdailey.qcaret.ui.swt.editor.SwtKeys;
import io.github.ajdailey.qcaret.ui.swt.search.ReplaceDialog;
import io.github.ajdailey.qcaret.ui.swt.search.ResultsDock;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.DeviceData;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tab open and close, theme switches, font zoom, the tab menu, menu syncs, split and unsplit,
 * folding and unfolding in both views of a split, the find bar opened, searching and closed, the
 * command palette, the completion list, the whitespace marks, a HyperSearch from its dialog into
 * the results dock, the replace preview opened and closed, the overview strip hidden and shown, and
 * a bookmark comment with its gutter tooltip and the Bookmarks list opened and hidden return the
 * Display's tracked graphics resources ({@code DeviceData.tracking}) to their count after a warm-up
 * round. Theme switches, zoom and split each rebuild or add a primary caret image. A failure lists
 * each leaked object's allocation stack.
 *
 * <p>System cursors are left out: the Display owns them, and on each GTK settings change (a theme
 * switch) it moves its cached cursors to a list it disposes with itself, so the next {@code
 * getSystemCursor} allocates another (measured: one per cursor kind in use per switch).
 */
@Tag("swt")
class ResourceLeakTest {
  private static final Duration BOUND = Duration.ofSeconds(20);
  private static final int CYCLES = 4;

  @TempDir Path temp;

  private final List<Throwable> failures = new CopyOnWriteArrayList<Throwable>();
  private Display display;
  private Workers workers;
  private MainWindow window;
  private ApplicationController controller;
  private int cycles;

  @BeforeEach
  void open() throws Exception {
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
    window = new MainWindow(display, Palette.LIGHT);
    controller =
        new ApplicationController(
            workers,
            new SwtUiDispatcher(display),
            window,
            new ConfigStore(new SettingsLocation(SettingsMode.EXPLICIT, temp.resolve("settings"))),
            Preferences.DEFAULTS,
            SwtThemeService.open(display, ThemeMode.LIGHT),
            new MemoryBudget(1L << 30, 60),
            Keymap.defaults(SwtKeys.mac()),
            false);
    window.attach(controller);
    window.place(new WindowGeometry.Request(0, 0, 800, 400), null);
    window.open();
    openTab("first.txt");
  }

  @AfterEach
  void close() {
    controller.quit();
    runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return window.shell().isDisposed();
          }
        });
    display.dispose();
    assertEquals(0, workers.liveThreads());
    assertTrue(failures.isEmpty(), failures.toString());
  }

  private boolean runUntil(BooleanSupplier condition) {
    return EventLoop.runUntil(display, condition, BOUND);
  }

  private void drain() {
    while (display.readAndDispatch()) {
      // Runs queued work.
    }
  }

  private void openTab(String name) throws IOException {
    Path file = temp.resolve(name);
    if (!Files.exists(file)) {
      StringBuilder text = new StringBuilder("lines linear\n");
      for (int i = 0; i < 200; i++) {
        text.append(i % 4 == 0 ? "line " : "  line ").append(i).append('\n');
      }
      Files.writeString(file, text.toString(), StandardCharsets.US_ASCII);
    }
    final int before = window.tabs().getItemCount();
    controller.documents().open(List.of(file));
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return window.tabs().getItemCount() == before + 1
                    && controller.documents().loading() == 0;
              }
            }),
        name);
    drain();
  }

  private void closeTab() {
    final int before = window.tabs().getItemCount();
    controller.execute(CommandId.CLOSE);
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return window.tabs().getItemCount() == before - 1;
              }
            }));
    drain();
  }

  private void theme(CommandId command, final Palette expected) {
    controller.execute(command);
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return window.palette().equals(expected);
              }
            }));
    drain();
  }

  /** Collapses every region in the focused view once its regions arrive. */
  private void foldAll() {
    final EditorController editor = controller.documents().activeEditor();
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return editor.projection().foldRegionCount() > 0;
              }
            }));
    controller.execute(CommandId.FOLD_ALL);
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return editor.projection().collapsedCount()
                    == editor.projection().foldRegionCount();
              }
            }));
    drain();
  }

  /**
   * Splits the tab, folds and unfolds in both views, finds with the bar open in both views, closes
   * the bar and unsplits, keeping the new view.
   */
  private void splitAndFind() {
    controller.execute(CommandId.SPLIT_VERTICAL);
    drain();
    assertEquals(2, window.activeEditors().size());
    for (EditorPane pane : window.activeEditors()) {
      assertFalse(pane.caretImage().isDisposed());
    }
    foldAll();
    controller.execute(CommandId.FOCUS_OTHER_VIEW);
    foldAll();
    controller.execute(CommandId.UNFOLD_ALL);
    controller.execute(CommandId.FOCUS_OTHER_VIEW);
    controller.execute(CommandId.UNFOLD_ALL);
    drain();
    controller.execute(CommandId.FIND);
    window.findBar().field().setText("line " + (cycles++ % 2));
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return !controller.find().state().running();
              }
            }));
    drain();
    controller.find().close();
    controller.execute(CommandId.UNSPLIT);
    drain();
  }

  /**
   * Opens and closes the command palette, and the completion list with the whitespace and
   * line-ending marks on.
   */
  private void paletteAndCompletion() {
    controller.execute(CommandId.COMMAND_PALETTE);
    drain();
    window.commandPalette().filter().setText("zoom");
    window.commandPalette().shell().close();
    drain();
    controller.execute(CommandId.SHOW_WHITESPACE);
    controller.execute(CommandId.SHOW_LINE_ENDINGS);
    final EditorPane pane = window.activeEditor();
    pane.text().setSelection(pane.text().getText().indexOf("line 1") + 2);
    pane.text().notifyListeners(SWT.Selection, new Event());
    controller.execute(CommandId.COMPLETE_WORD);
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return !pane.completionWords().isEmpty();
              }
            }));
    pane.text().redraw();
    pane.text().update();
    controller.execute(CommandId.CANCEL);
    assertTrue(pane.completionWords().isEmpty());
    controller.execute(CommandId.SHOW_WHITESPACE);
    controller.execute(CommandId.SHOW_LINE_ENDINGS);
    drain();
  }

  /**
   * Runs a HyperSearch from the dialog, shows a match row of the dock, hides and shows the overview
   * strip, then clears and hides the dock.
   */
  private void searchAndStrip() {
    controller.execute(CommandId.HYPERSEARCH);
    drain();
    window.searchDialog().text().setText("line " + (cycles % 3));
    window.searchDialog().search();
    final ResultsInput results = controller.search();
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return results.setCount() > 0 && !results.running(0);
              }
            }));
    drain();
    ResultsDock dock = window.resultsDock();
    dock.activate(dock.row(0, 0));
    dock.table().showItem(dock.table().getItem(dock.row(0, 0, 0)));
    dock.table().redraw();
    dock.table().update();
    controller.execute(CommandId.OVERVIEW_STRIP);
    drain();
    controller.execute(CommandId.OVERVIEW_STRIP);
    drain();
    controller.execute(CommandId.REPLACE_IN_RESULTS);
    final ReplaceDialog preview = window.replaceDialog();
    preview.setReplacement("lane");
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return controller.replace().preview() != null
                    && preview.previewText().contains("lane");
              }
            }));
    preview.shell().dispose();
    drain();
    results.clearAll();
    controller.execute(CommandId.SEARCH_RESULTS);
    drain();
  }

  /**
   * Comments the caret's line through the prompt, shows its tooltip over the gutter, opens the
   * Bookmarks list until it shows the row, and hides it.
   */
  private void bookmarkList() {
    final EditorPane pane = window.activeEditor();
    pane.text().setTopIndex(0);
    pane.text().setSelection(0);
    pane.text().notifyListeners(SWT.Selection, new Event());
    controller.execute(CommandId.EDIT_BOOKMARK_COMMENT);
    window.commentDialog().field().setText("mock note " + cycles);
    window.commentDialog().ok().notifyListeners(SWT.Selection, new Event());
    Event move = new Event();
    move.x = 2;
    move.y = pane.text().getLinePixel(0) + 2;
    pane.text().notifyListeners(SWT.MouseMove, move);
    assertEquals("mock note " + cycles, pane.text().getToolTipText());
    pane.text().notifyListeners(SWT.MouseExit, new Event());
    controller.execute(CommandId.BOOKMARKS_LIST);
    final BookmarksDock dock = window.bookmarksDock();
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return dock.table().getItemCount() > 0
                    && dock.cells(0)[1].equals("mock note " + cycles);
              }
            }));
    dock.table().redraw();
    dock.table().update();
    controller.execute(CommandId.CLEAR_BOOKMARKS);
    controller.execute(CommandId.BOOKMARKS_LIST);
    drain();
  }

  /** One round of every operation; the first rounds create lazily cached resources. */
  private void cycle() throws IOException {
    openTab("second.txt");
    window.showTabMenu(1).setVisible(false);
    closeTab();
    Image caret = window.activeEditor().caretImage();
    theme(CommandId.THEME_DARK, Palette.DARK);
    theme(CommandId.THEME_LIGHT, Palette.LIGHT);
    controller.execute(CommandId.ZOOM_IN);
    controller.execute(CommandId.ZOOM_OUT);
    assertTrue(caret.isDisposed());
    assertFalse(window.activeEditor().caretImage().isDisposed());
    splitAndFind();
    paletteAndCompletion();
    searchAndStrip();
    bookmarkList();
    for (CommandGroup group : CommandGroup.values()) {
      if (group.menu()) {
        window.menuBar().menu(group).notifyListeners(SWT.Show, new Event());
      }
    }
    window.documentStateChanged();
    drain();
  }

  @Test
  void tabsThemesZoomAndMenusReleaseTheirResources() throws IOException {
    // The second round adds one StyledText line layout to the widget's cache, whatever the count
    // of later rounds (measured over 4 and 9), so the baseline follows two.
    cycle();
    cycle();
    Map<Object, Error> baseline = tracked();
    Map<String, Integer> types = counts(baseline.keySet());
    assertTrue(types.containsKey("Font") && types.containsKey("Image"), "tracked " + types);
    for (int i = 0; i < CYCLES; i++) {
      cycle();
    }
    Map<Object, Error> after = tracked();
    assertEquals(counts(baseline.keySet()), counts(after.keySet()), leaks(baseline, after));
  }

  /** The live tracked resources with their allocation stacks, system cursors left out. */
  private Map<Object, Error> tracked() {
    DeviceData data = display.getDeviceData();
    Map<Object, Error> live = new IdentityHashMap<Object, Error>();
    for (int i = 0; i < data.objects.length; i++) {
      if (!systemCursor(data.errors[i])) {
        live.put(data.objects[i], data.errors[i]);
      }
    }
    return live;
  }

  private static boolean systemCursor(Error allocation) {
    for (StackTraceElement frame : allocation.getStackTrace()) {
      if (frame.getMethodName().equals("getSystemCursor")
          && frame.getClassName().equals(Display.class.getName())) {
        return true;
      }
    }
    return false;
  }

  private static Map<String, Integer> counts(Iterable<Object> objects) {
    Map<String, Integer> counts = new TreeMap<String, Integer>();
    for (Object object : objects) {
      counts.merge(object.getClass().getSimpleName(), 1, Integer::sum);
    }
    return counts;
  }

  /** The type and allocation stack of each resource live after the cycles and not before. */
  private static String leaks(Map<Object, Error> baseline, Map<Object, Error> after) {
    List<String> found = new ArrayList<String>();
    for (Map.Entry<Object, Error> entry : after.entrySet()) {
      if (!baseline.containsKey(entry.getKey())) {
        StringWriter stack = new StringWriter();
        entry.getValue().printStackTrace(new PrintWriter(stack));
        found.add(entry.getKey().getClass().getSimpleName() + " allocated at\n" + stack);
      }
    }
    return found.size() + " resources not in the baseline:\n" + String.join("\n", found);
  }
}
