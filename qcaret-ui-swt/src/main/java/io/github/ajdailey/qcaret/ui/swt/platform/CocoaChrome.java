package io.github.ajdailey.qcaret.ui.swt.platform;

import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import java.util.HashSet;
import java.util.Set;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.List;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Spinner;
import org.eclipse.swt.widgets.TabFolder;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.Tree;

/**
 * Keeps every shell readable while the pinned appearance differs from the one cocoa SWT read its
 * widget colors under, the system appearance at Display creation. AppKit draws native text in the
 * pinned appearance, while SWT paints composites, tabs, lists and fields with the colors it read.
 * So while the two differ, each shell's background is the window color of the pinned appearance,
 * forced onto its children. That inheritance stops at a {@link TabFolder}, so check boxes turn
 * transparent over its native panel, its titles get the text color, and tables, trees, lists and
 * text fields get the list colors. In the appearance SWT read every color returns to the default.
 * Menus stay native, and so does a read-only {@link Combo}, an {@code NSPopUpButton} that AppKit
 * draws in the pinned appearance: SWT writes a Combo's foreground into each item title of its menu,
 * where a dark menu draws white as {@code #656565}. A {@link Canvas} that paints from the palette
 * keeps its colors, as does a control with {@link ChromeColors#PALETTE_COLORED} set.
 *
 * <p>Table and tree headers always carry explicit list colors of the pinned appearance, from
 * creation on: a null header draws blank labels in dark. In the appearance SWT read they are the
 * Display's list colors, the header's own default. A visible {@link Table} header repaints new
 * colors only when shown again, so a change hides and shows it; a {@link Tree} repaints at once.
 *
 * <p>A control created while the two differ, as the docks' replaced tables are, takes its colors
 * from {@link SWT#Skin}. SWT sends Skin from {@code readAndDispatch} and layout, neither of which
 * runs while a menu tracks, so a control created then by a timer keeps the cached side. A control
 * whose side is stale therefore also takes its colors when shown and, while any menu shows, at a
 * sweep of every shell each {@link #SWEEP_MILLIS} through the {@link UiDispatcher} timer, which
 * runs during tracking. Each control of a recolored shell receives {@link SWT#Settings}, since the
 * Display sends none for an appearance change. Confined to the UI thread.
 */
final class CocoaChrome {
  /** {@code COLOR_WIDGET_BACKGROUND} and {@code COLOR_LIST_BACKGROUND}, measured on macOS 15. */
  private static final Color DARK_WINDOW = new Color(0x32, 0x32, 0x32);

  private static final Color DARK_LIST = new Color(0x1e, 0x1e, 0x1e);
  private static final Color DARK_TEXT = new Color(0xff, 0xff, 0xff);
  private static final Color LIGHT_WINDOW = new Color(0xec, 0xec, 0xec);
  private static final Color LIGHT_LIST = new Color(0xff, 0xff, 0xff);
  private static final Color LIGHT_TEXT = new Color(0, 0, 0);
  private static final Color CLEAR = new Color(0, 0, 0, 0);

  /** Control data: the side, true for dark, the control's colors were last set for. */
  static final String STYLED = CocoaChrome.class.getName() + ".styled";

  /** Interval of the stale-control sweep while a menu shows. */
  static final int SWEEP_MILLIS = 100;

  private final Display display;
  private final UiDispatcher ui;
  private final boolean cachedDark;
  private boolean dark;

  /** Menus shown and not yet hidden; the sweep runs while one is. */
  private final Set<Menu> menusShown = new HashSet<Menu>();

  private final Runnable sweep =
      new Runnable() {
        @Override
        public void run() {
          menusShown.removeIf(Menu::isDisposed);
          if (menusShown.isEmpty() || display.isDisposed()) {
            return;
          }
          for (Shell shell : display.getShells()) {
            coverStale(shell);
          }
          ui.timerExec(SWEEP_MILLIS, this);
        }
      };

  /**
   * Creates the chrome and covers each shell as it is shown and each control as it is created.
   *
   * @param display the Display
   * @param cachedDark the appearance SWT read its widget colors under
   * @param ui runs the sweep while a menu shows
   */
  CocoaChrome(Display display, boolean cachedDark, UiDispatcher ui) {
    this.display = display;
    this.ui = ui;
    this.cachedDark = cachedDark;
    this.dark = cachedDark;
    display.addFilter(
        SWT.Show,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            if (event.widget instanceof Shell shell) {
              if (differs()) {
                restyle(shell);
              }
            } else if (event.widget instanceof Menu menu) {
              // A menu bar sends SWT.Show with its shell and no SWT.Hide.
              if ((menu.getStyle() & SWT.BAR) == 0) {
                if (menusShown.isEmpty()) {
                  ui.timerExec(SWEEP_MILLIS, sweep);
                }
                menusShown.add(menu);
              }
            } else if (event.widget instanceof Control control && stale(control)) {
              cover(control);
            }
          }
        });
    display.addFilter(
        SWT.Hide,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            if (event.widget instanceof Menu menu && menusShown.remove(menu)) {
              menusShown.removeIf(Menu::isDisposed);
              if (menusShown.isEmpty()) {
                ui.cancelTimer(sweep);
              }
            }
          }
        });
    display.addListener(
        SWT.Skin,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            if (event.widget instanceof Control control) {
              cover(control);
            }
          }
        });
  }

  /**
   * Applies the pinned appearance to every shell.
   *
   * @param value true for dark
   */
  void apply(boolean value) {
    dark = value;
    for (Shell shell : display.getShells()) {
      restyle(shell);
    }
  }

  private boolean differs() {
    return dark != cachedDark;
  }

  /** Returns true when a control's colors were not set for the pinned side. */
  private boolean stale(Control control) {
    return !(control instanceof Shell)
        && control.getData(ChromeColors.PALETTE_COLORED) == null
        && !Boolean.valueOf(dark).equals(control.getData(STYLED));
  }

  /** Colors each stale control of a composite and its descendants. */
  private void coverStale(Control control) {
    if (stale(control)) {
      cover(control);
    }
    if (control instanceof Composite composite) {
      for (Control child : composite.getChildren()) {
        coverStale(child);
      }
    }
  }

  /** Colors a control created or shown after its shell was styled. */
  private void cover(Control control) {
    if (control instanceof Shell) {
      return;
    }
    if (differs()) {
      style(control);
    } else {
      header(control);
    }
  }

  private void restyle(Shell shell) {
    shell.setBackgroundMode(differs() ? SWT.INHERIT_FORCE : SWT.INHERIT_NONE);
    shell.setBackground(differs() ? (dark ? DARK_WINDOW : LIGHT_WINDOW) : null);
    walk(shell);
  }

  private void walk(Control control) {
    style(control);
    if (control instanceof Composite composite) {
      for (Control child : composite.getChildren()) {
        walk(child);
      }
    }
    control.notifyListeners(SWT.Settings, new Event());
  }

  private void style(Control control) {
    if (control.getData(ChromeColors.PALETTE_COLORED) != null) {
      return;
    }
    Color list = differs() ? (dark ? DARK_LIST : LIGHT_LIST) : null;
    Color text = differs() ? (dark ? DARK_TEXT : LIGHT_TEXT) : null;
    if (control instanceof CTabFolder tabs) {
      tabs.setForeground(text);
      tabs.setSelectionForeground(text);
      tabs.setSelectionBackground(list);
    } else if (control instanceof Table
        || control instanceof Tree
        || control instanceof List
        || (control instanceof Combo && (control.getStyle() & SWT.READ_ONLY) == 0)
        || control instanceof Text
        || control instanceof Spinner
        || control instanceof StyledText) {
      control.setBackground(list);
      control.setForeground(text);
    } else if (control instanceof TabFolder) {
      control.setForeground(text);
    } else if (control instanceof Button && (control.getStyle() & SWT.CHECK) != 0) {
      control.setBackground(differs() ? CLEAR : null);
    }
    header(control);
  }

  private void header(Control control) {
    control.setData(STYLED, dark);
    Color background =
        differs()
            ? (dark ? DARK_LIST : LIGHT_LIST)
            : display.getSystemColor(SWT.COLOR_LIST_BACKGROUND);
    Color foreground =
        differs()
            ? (dark ? DARK_TEXT : LIGHT_TEXT)
            : display.getSystemColor(SWT.COLOR_LIST_FOREGROUND);
    if (control instanceof Table table) {
      table.setHeaderBackground(background);
      table.setHeaderForeground(foreground);
      Object before = table.getData(ThemeFollow.HEADER_DARK);
      table.setData(ThemeFollow.HEADER_DARK, dark);
      if (table.getHeaderVisible() && !Boolean.valueOf(dark).equals(before)) {
        table.setHeaderVisible(false);
        table.setHeaderVisible(true);
      }
    } else if (control instanceof Tree tree) {
      tree.setHeaderBackground(background);
      tree.setHeaderForeground(foreground);
    }
  }
}
