package io.github.ajdailey.qcaret.ui.swt;

import io.github.ajdailey.qcaret.app.controller.ApplicationController;
import io.github.ajdailey.qcaret.app.controller.StagedTabs;
import io.github.ajdailey.qcaret.app.view.WindowGeometry;
import io.github.ajdailey.qcaret.app.view.WorkspaceNode;
import io.github.ajdailey.qcaret.ui.swt.platform.WindowRebuild;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;

/**
 * Rebuilds the main windows for a live switch of the native theme on win32, where native controls
 * read it only when created. Each window's {@link MainWindowSlot} gets a new {@link MainWindow} for
 * the same {@link ApplicationController}, so documents, workers, history and results stay.
 *
 * <p>{@link #rebuild} works on later UI turns, one window at a time, and only on a window built
 * under the other side of {@link SwtThemeService#nativeDark}. One turn creates the new window,
 * hidden; each later turn builds one tab's views in it ({@link StagedTabs}), while the old window
 * stays shown and takes all input. The last turn swaps: it carries the bounds, maximized, full
 * screen and minimized state, the dock weights, the Workspace Browser's expanded folders, the
 * results tree's expanded groups and selected row, the slot's pushed state, the tabs ({@link
 * io.github.ajdailey.qcaret.app.controller.DocumentController#rebindViews}) and the active window,
 * opens the new shell and hides the old one, which the next turn disposes, so the event loop always
 * holds an open shell. Other windows the old shell owns close with it, which cancels a running
 * checksum or statistics job.
 *
 * <p>The create and swap turns wait, rechecked every {@link #RECHECK_MILLIS}, while the window
 * shows a menu ({@link ShownMenus}) or owns a visible window {@link #holdWhile} marks as holding
 * input; the swap also waits while {@link MainWindowSlot#holdsDialog} reports a dialog it would
 * dispose. A switch back during a build disposes the new window; a later switch starts over.
 *
 * <p>From {@link #rebuild} until the swap, the old window's selected tab keeps its editor colors
 * ({@link #replacing}): a {@code StyledText} color change costs O(lines), seconds for a file of
 * millions of lines, and the swap disposes those editors. A window that waits for a menu or a
 * dialog takes the new colors at once. Confined to the UI thread.
 */
public final class WindowViews implements WindowRebuild {
  /** Milliseconds between checks of a window that waits for a dialog to close. */
  static final int RECHECK_MILLIS = 250;

  /**
   * Shell data key: a {@link BooleanSupplier} that is true while the shell's disposal loses input.
   */
  private static final String HOLD = WindowViews.class.getName() + ".hold";

  /** Shell data key: {@code Boolean.TRUE} while a rebuild replaces the main window. */
  private static final String REPLACING = WindowViews.class.getName() + ".replacing";

  /** A hold for a window that holds input while it is open. */
  static final BooleanSupplier WHILE_OPEN =
      new BooleanSupplier() {
        @Override
        public boolean getAsBoolean() {
          return true;
        }
      };

  /** Creates a replacement window. */
  public interface Factory {
    /**
     * Creates a window attached to a controller and registered with the event loop, not yet placed
     * or open.
     *
     * @param controller the window's controller
     * @return the window
     */
    MainWindow create(ApplicationController controller);
  }

  /** A replacement window under construction. */
  private static final class Build {
    final MainWindow next;
    final boolean dark;
    final StagedTabs tabs;
    boolean staged;

    Build(MainWindow next, boolean dark, StagedTabs tabs) {
      this.next = next;
      this.dark = dark;
      this.tabs = tabs;
    }
  }

  private static final class Entry {
    final MainWindowSlot slot;
    final ApplicationController controller;
    boolean builtDark;
    Build build;

    Entry(MainWindowSlot slot, ApplicationController controller, boolean builtDark) {
      this.slot = slot;
      this.controller = controller;
      this.builtDark = builtDark;
    }
  }

  private final Display display;
  private final SwtUiDispatcher ui;
  private final Factory factory;
  private final ShownMenus menus;
  private final List<Entry> entries = new ArrayList<Entry>();
  private final Runnable step =
      new Runnable() {
        @Override
        public void run() {
          scheduled = false;
          step();
        }
      };
  private final Runnable recheck =
      new Runnable() {
        @Override
        public void run() {
          timer = false;
          schedule();
        }
      };
  private boolean scheduled;
  private boolean timer;
  private int rebuilds;

  /**
   * Creates the window side of a rebuild.
   *
   * @param display the Display
   * @param factory creates replacement windows
   */
  public WindowViews(Display display, Factory factory) {
    this(display, factory, new ShownMenus(display));
  }

  /**
   * Creates the window side of a rebuild over a given menu tracker.
   *
   * @param display the Display
   * @param factory creates replacement windows
   * @param menus the Display's shown menus
   */
  WindowViews(Display display, Factory factory, ShownMenus menus) {
    this.display = display;
    this.ui = new SwtUiDispatcher(display);
    this.factory = factory;
    this.menus = menus;
  }

  /**
   * Marks a window owned by a main window whose disposal loses the user's input while a condition
   * holds, so the main window's rebuild waits for it.
   *
   * @param shell the window
   * @param holding true while closing the window would lose input; read on the UI thread
   */
  static void holdWhile(Shell shell, BooleanSupplier holding) {
    shell.setData(HOLD, holding);
  }

  /**
   * Reports whether a main window owns a visible window {@link #holdWhile} marks while it holds
   * input.
   *
   * @param shell the main window's shell
   * @return true while one holds
   */
  static boolean held(Shell shell) {
    for (Shell owned : shell.getShells()) {
      if (!owned.isDisposed()
          && owned.isVisible()
          && owned.getData(HOLD) instanceof BooleanSupplier holding
          && holding.getAsBoolean()) {
        return true;
      }
    }
    return false;
  }

  /** Reports whether the window shows a menu or a window that holds input. */
  private boolean inUse(Entry e) {
    Shell shell = e.slot.window().shell();
    return menus.shownOver(shell) || held(shell);
  }

  /**
   * Adds a window, built under the native setting now.
   *
   * @param slot the window's slot
   * @param controller the controller over the slot
   */
  public void add(MainWindowSlot slot, ApplicationController controller) {
    entries.add(new Entry(slot, controller, SwtThemeService.nativeDark(display)));
  }

  /**
   * Reports whether a rebuild replaces a main window, so its selected tab keeps its editor colors.
   *
   * @param shell the main window's shell
   * @return true from {@link #rebuild} until the swap, or until the window waits for a menu or a
   *     dialog
   */
  static boolean replacing(Shell shell) {
    return Boolean.TRUE.equals(shell.getData(REPLACING));
  }

  @Override
  public void rebuild() {
    boolean dark = SwtThemeService.nativeDark(display);
    for (Entry e : entries) {
      if (!closed(e)) {
        // A switch back ends the replacement; the palette change that follows recolors the tab.
        e.slot.window().shell().setData(REPLACING, e.builtDark != dark ? Boolean.TRUE : null);
      }
    }
    schedule();
  }

  /** Ends {@link #replacing} for a window and gives its selected tab the colors it kept back. */
  private static void release(Entry e) {
    MainWindow window = e.slot.window();
    if (!replacing(window.shell())) {
      return;
    }
    window.shell().setData(REPLACING, null);
    for (CTabItem item : window.tabs().getItems()) {
      if (item.getData() instanceof EditorTab tab) {
        tab.applyPending();
      }
    }
  }

  /**
   * Counts the windows rebuilt so far.
   *
   * @return the count
   */
  int rebuilds() {
    return rebuilds;
  }

  /**
   * Reports whether a window still waits for its rebuild.
   *
   * @return true while one does
   */
  boolean pending() {
    boolean dark = SwtThemeService.nativeDark(display);
    for (Entry e : entries) {
      if (e.builtDark != dark && !closed(e)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Returns the replacement window under construction for a slot.
   *
   * @param slot the slot
   * @return the hidden window, or null when none is built
   */
  MainWindow building(MainWindowSlot slot) {
    for (Entry e : entries) {
      if (e.slot == slot && e.build != null) {
        return e.build.next;
      }
    }
    return null;
  }

  private void schedule() {
    if (!scheduled && !display.isDisposed()) {
      scheduled = true;
      ui.asyncExec(step);
    }
  }

  /** Does one turn's work: at most one window created, one tab staged or one swap. */
  private void step() {
    boolean dark = SwtThemeService.nativeDark(display);
    boolean waiting = false;
    for (Entry e : new ArrayList<Entry>(entries)) {
      boolean gone = closed(e);
      // Hidden while its quit completes.
      boolean hidden = !gone && !e.slot.window().shell().getVisible();
      if (e.build != null && (gone || hidden || e.build.dark != dark)) {
        cancel(e);
      }
      if (gone) {
        entries.remove(e);
      } else if (e.builtDark != dark) {
        if (hidden) {
          e.builtDark = dark;
        } else if (e.build == null && inUse(e)) {
          release(e);
          waiting = true;
        } else if (e.build == null) {
          start(e, dark);
          schedule();
          return;
        } else if (!e.build.staged) {
          e.build.staged = e.build.tabs.step();
          schedule();
          return;
        } else if (e.slot.holdsDialog() || inUse(e)) {
          release(e);
          waiting = true;
        } else {
          swap(e);
          schedule();
          return;
        }
      }
    }
    if (waiting && !timer) {
      timer = true;
      ui.timerExec(RECHECK_MILLIS, recheck);
    }
  }

  private static boolean closed(Entry e) {
    return e.slot.window().shell().isDisposed() || e.controller.isQuitting();
  }

  private void start(Entry e, boolean dark) {
    MainWindow next = factory.create(e.controller);
    next.shell().setBounds(e.slot.window().shell().getBounds());
    e.build = new Build(next, dark, e.controller.documents().stageViews(next));
  }

  private static void cancel(Entry e) {
    e.build.tabs.cancel();
    e.build.next.shell().dispose();
    e.build = null;
  }

  private void swap(Entry e) {
    MainWindow next = e.build.next;
    StagedTabs tabs = e.build.tabs;
    e.builtDark = e.build.dark;
    e.build = null;
    MainWindow old = e.slot.window();
    Shell oldShell = old.shell();
    boolean active = display.getActiveShell() == oldShell;
    boolean minimized = oldShell.getMinimized();
    boolean maximized = old.windowMaximized();
    boolean fullScreen = old.fullScreen();
    WindowGeometry.Request normal = old.windowBounds();
    Rectangle bounds = oldShell.getBounds();
    int[] side = old.sideWeights();
    int[] docks = old.dockWeights();
    int[] results = old.resultsWeights();
    List<WorkspaceNode> expanded = old.expandedWorkspace();
    if (maximized || fullScreen) {
      next.place(normal, null);
      next.shell().setMaximized(maximized);
    } else {
      next.shell().setBounds(bounds);
    }
    e.slot.replace(next);
    next.setSideWeights(side);
    next.setDockWeights(docks);
    next.setResultsWeights(results);
    for (WorkspaceNode directory : expanded) {
      next.expandWorkspace(directory);
    }
    next.resultsDock().takeState(old.resultsDock());
    e.controller.documents().rebindViews(tabs);
    next.open();
    if (fullScreen) {
      next.setFullScreen(true);
    }
    if (minimized) {
      next.shell().setMinimized(true);
    }
    oldShell.setVisible(false);
    if (active && !minimized) {
      next.shell().setActive();
    }
    next.showStatusNow();
    ui.asyncExec(
        new Runnable() {
          @Override
          public void run() {
            oldShell.dispose();
            rebuilds++;
          }
        });
  }
}
