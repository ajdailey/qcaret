package io.github.ajdailey.qcaret.ui.swt;

import io.github.ajdailey.qcaret.app.controller.ApplicationController;
import io.github.ajdailey.qcaret.app.editor.DisplayProjection;
import io.github.ajdailey.qcaret.app.theme.Palette;
import io.github.ajdailey.qcaret.app.theme.Rgb;
import io.github.ajdailey.qcaret.app.view.EditorInput;
import io.github.ajdailey.qcaret.app.view.TabInput;
import io.github.ajdailey.qcaret.app.view.TabView;
import io.github.ajdailey.qcaret.core.document.SelectionModel;
import io.github.ajdailey.qcaret.ui.swt.editor.EditorPane;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.CTabFolder2Adapter;
import org.eclipse.swt.custom.CTabFolderEvent;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.Shell;

/**
 * The main window's document tabs: a {@link CTabFolder} with one {@link EditorTab} per document,
 * each holding one {@link EditorPane} or two in a {@link SashForm}.
 *
 * <p>Tab close buttons and a middle click ask the controller to close; dragging a tab within the
 * strip moves it, and a release off the strip moves it to another window ({@link #dropTab}). A
 * tab's item cannot change position, so a move disposes and recreates it, and the {@code Selection}
 * that {@code CTabFolder} sends for a neighbor while the selected item is disposed is ignored
 * (QLogg UI-14). One {@link TabMenu} serves every tab; each right click replaces its items. Created
 * and used on the UI thread.
 */
final class TabStrip {
  /** Shell data naming the shell's tab strip, for a tab dropped on it. */
  private static final String WINDOW = "qcaret.window";

  /** The window around the strip. */
  interface Host {
    /**
     * Creates an editor in a tab with the window's editor settings.
     *
     * @param sash the tab's control
     * @param projection the document's projection
     * @param selections the view's selections
     * @param editor the view's controller
     * @return the editor
     */
    EditorPane newPane(
        SashForm sash, DisplayProjection projection, SelectionModel selections, EditorInput editor);

    /**
     * Opens the editor menu over a view, which is already its tab's focused view.
     *
     * @param pane the view
     * @param editor the view's controller
     * @param event the {@code MenuDetect} event
     */
    void showEditorMenu(EditorPane pane, EditorInput editor, Event event);

    /** Shows the status fields on a later UI turn. */
    void scheduleStatus();

    /**
     * Returns the window's controller.
     *
     * @return the controller, or null before the window is attached
     */
    ApplicationController controller();

    /**
     * Returns the tabs' context menu.
     *
     * @return the menu, or null before the window is attached
     */
    TabMenu tabMenu();
  }

  private final Shell shell;
  private final CTabFolder tabs;
  private final Host host;

  /**
   * Disposes a closed tab's widgets after the close; GTK widget teardown dominates a bulk close.
   */
  private final DeferredDisposal closedTabs;

  private CTabItem dragged;
  private boolean movingTab;
  // Tab close: the controller picks the tab shown next.
  private boolean removingTab;
  private MainWindow.PointerProbe pointer =
      new MainWindow.PointerProbe() {
        @Override
        public Control controlAt(Point point) {
          return shell.getDisplay().getCursorControl();
        }
      };

  /**
   * Creates the folder as the parent's next child and names the strip in the shell's data. Tab
   * selection, close buttons, middle clicks, the context menu and drags go to the controller.
   *
   * @param parent the window's body
   * @param dispatcher disposes closed tabs' widgets in later UI tasks
   * @param host the window
   */
  TabStrip(Composite parent, SwtUiDispatcher dispatcher, Host host) {
    this.shell = parent.getShell();
    this.host = host;
    this.closedTabs = new DeferredDisposal(dispatcher);
    shell.setData(WINDOW, this);
    tabs = new CTabFolder(parent, SWT.TOP | SWT.FLAT);
    tabs.setData(EditorPane.PALETTE_COLORED, Boolean.TRUE);
    tabs.addListener(
        SWT.Selection,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            if (!movingTab
                && !removingTab
                && event.item != null
                && event.item.getData() instanceof EditorTab tab) {
              tab.pane.focus();
              tab.input.activated();
              host.scheduleStatus();
            }
          }
        });
    tabs.addCTabFolder2Listener(
        new CTabFolder2Adapter() {
          @Override
          public void close(CTabFolderEvent event) {
            event.doit = false;
            if (event.item.getData() instanceof EditorTab tab) {
              tab.input.closeRequested();
            }
          }
        });
    tabs.addListener(
        SWT.MouseDown,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            dragged = event.button == 1 ? tabs.getItem(new Point(event.x, event.y)) : null;
          }
        });
    tabs.addListener(
        SWT.MenuDetect,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            CTabItem item = tabs.getItem(tabs.toControl(event.x, event.y));
            if (item != null && item.getData() instanceof EditorTab tab) {
              event.doit = false;
              showTabMenu(tab, event.x, event.y);
            }
          }
        });
    tabs.addListener(
        SWT.MouseUp,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            CTabItem target = tabs.getItem(new Point(event.x, event.y));
            if (event.button == 2) {
              if (target != null && target.getData() instanceof EditorTab tab) {
                tab.input.closeRequested();
              }
              return;
            }
            CTabItem source = dragged;
            dragged = null;
            if (source == null || source.isDisposed()) {
              return;
            }
            if (target == null) {
              Point point = tabs.toDisplay(event.x, event.y);
              dropTab((EditorTab) source.getData(), point, pointer.controlAt(point));
            } else if (target != source) {
              moveTab(source, tabs.indexOf(target));
            }
          }
        });
  }

  /**
   * Returns the folder.
   *
   * @return the folder holding one item per document
   */
  CTabFolder folder() {
    return tabs;
  }

  /**
   * Adds a document's tab, selected, and focuses its editor.
   *
   * @param projection the document's projection
   * @param selections the first view's selections
   * @param editor the first view's controller
   * @param input the tab's controller
   * @param index the index, clamped to the tab count
   * @return the tab
   */
  TabView addTab(
      DisplayProjection projection,
      SelectionModel selections,
      EditorInput editor,
      TabInput input,
      int index) {
    EditorTab tab =
        new EditorTab(
            this,
            host,
            new SashForm(tabs, SWT.VERTICAL),
            input,
            Math.min(index, tabs.getItemCount()));
    EditorPane pane = tab.add(projection, selections, editor);
    tabs.setSelection(tab.item);
    pane.focus();
    return tab;
  }

  /**
   * Returns the selected tab's focused editor.
   *
   * @return the editor, or null without a tab
   */
  EditorPane activeEditor() {
    CTabItem item = tabs.getSelection();
    return item != null && item.getData() instanceof EditorTab tab ? tab.pane : null;
  }

  /**
   * Returns the selected tab's editor views.
   *
   * @return the views, empty without a tab
   */
  List<EditorPane> activeEditors() {
    CTabItem item = tabs.getSelection();
    return item != null && item.getData() instanceof EditorTab tab
        ? new ArrayList<EditorPane>(tab.panes)
        : new ArrayList<EditorPane>();
  }

  /**
   * Returns every editor of every tab, in strip order.
   *
   * @return the editors
   */
  List<EditorPane> allPanes() {
    List<EditorPane> panes = new ArrayList<EditorPane>();
    for (CTabItem item : tabs.getItems()) {
      if (item.getData() instanceof EditorTab tab) {
        panes.addAll(tab.panes);
      }
    }
    return panes;
  }

  /**
   * Colors the strip. {@code CTabFolder} otherwise keeps the system list colors of its creation for
   * the selected tab, whatever the theme. It draws the selection background only while focus is
   * inside the folder; elsewhere the selected tab has the strip's background, and the label and
   * close button keep the selection foreground.
   *
   * @param next the palette
   */
  void colorTabs(Palette next) {
    tabs.setBackground(color(next.gutter()));
    tabs.setForeground(color(next.tabText()));
    tabs.setSelectionBackground(color(next.selection()));
    tabs.setSelectionForeground(color(next.selectionText()));
  }

  /**
   * Disposes a closed tab's item. The {@code Selection} the folder sends for a neighbor is ignored;
   * the controller picks the tab shown next.
   *
   * @param item the item
   */
  void removeItem(CTabItem item) {
    removingTab = true;
    try {
      item.dispose();
    } finally {
      removingTab = false;
    }
  }

  /**
   * Hides a closed tab's control and disposes it in later UI tasks.
   *
   * @param control the tab's control
   */
  void disposeLater(Control control) {
    closedTabs.dispose(control);
  }

  private static Color color(Rgb rgb) {
    return new Color(rgb.red(), rgb.green(), rgb.blue());
  }

  /**
   * Creates a tab's item at an index, holding the tab as its data.
   *
   * @param tab the tab
   * @param index the index
   * @return the item
   */
  CTabItem newItem(EditorTab tab, int index) {
    CTabItem item = new CTabItem(tabs, SWT.CLOSE, index);
    item.setControl(tab.sash);
    item.setData(tab);
    item.setText(tab.title.replace("&", "&&"));
    item.setToolTipText(tab.toolTip.replace("&", "&&"));
    return item;
  }

  /**
   * Sets how a released tab drag finds the control under it; for tests.
   *
   * @param probe the probe
   */
  void setPointerProbe(MainWindow.PointerProbe probe) {
    pointer = probe;
  }

  /**
   * Ends a tab drag released off this window's tabs. Over another main window the tab moves there:
   * before the tab under the point, else after the last tab. Outside every QCaret shell and outside
   * this window's bounds it moves to a new window with its top-left corner at the point. A release
   * anywhere else does nothing.
   */
  private void dropTab(EditorTab tab, Point point, Control under) {
    if (under == null) {
      if (!shell.getBounds().contains(point)) {
        host.controller().moveTabToNewWindow(tab.input, point.x, point.y);
      }
      return;
    }
    Shell other = under.getShell();
    if (other == shell
        || !(other.getData(WINDOW) instanceof TabStrip target)
        || target.host.controller() == null) {
      return;
    }
    int index = target.tabs.getItemCount();
    CTabItem item = target.tabs.getItem(target.tabs.toControl(point));
    if (item != null) {
      index = target.tabs.indexOf(item);
    }
    host.controller().moveTab(tab.input, target.host.controller(), index);
  }

  private Menu showTabMenu(final EditorTab tab, int x, int y) {
    return host.tabMenu().show(tab.input, x, y);
  }

  /**
   * Opens the context menu of the tab at an index; for tests.
   *
   * @param index the tab index
   * @return the menu, already visible
   */
  Menu showTabMenu(int index) {
    Point at = tabs.toDisplay(0, 0);
    return showTabMenu((EditorTab) tabs.getItem(index).getData(), at.x, at.y);
  }

  /**
   * Recreates a tab at another index. Disposing the selected item makes {@code CTabFolder} select a
   * neighbor and send {@code Selection}; the listener ignores it while {@link #movingTab} is set.
   *
   * @param source the dragged item
   * @param index the drop index
   */
  void moveTab(CTabItem source, int index) {
    EditorTab tab = (EditorTab) source.getData();
    boolean selected = tabs.getSelection() == source;
    movingTab = true;
    try {
      source.dispose();
      tab.item = newItem(tab, index);
      if (selected) {
        tabs.setSelection(tab.item);
      }
    } finally {
      movingTab = false;
    }
    tab.input.moved(index);
  }
}
