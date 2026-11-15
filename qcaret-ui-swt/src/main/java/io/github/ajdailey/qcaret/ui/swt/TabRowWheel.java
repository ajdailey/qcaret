package io.github.ajdailey.qcaret.ui.swt;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;

/**
 * The mouse wheel over a {@code CTabFolder}'s tab row selects the previous or next tab.
 *
 * <p>A {@code Display} filter takes every vertical wheel event whose pointer position lies over the
 * row, whichever control of the folder's shell receives it, and cancels it for that control. Win32
 * sends the wheel to the focused control, so a listener on the row's own controls acts only after a
 * click focuses them.
 *
 * <p>The event's {@code count} is added to an accumulator; each time its magnitude reaches {@link
 * #STEP} one tab is selected, toward the next tab for a negative count, and {@link #STEP} is
 * subtracted. A notch on GTK and win32 sends one event of about 3; a cocoa trackpad gesture sends
 * about 200 events of 1 or 2. A reversal restarts the accumulator at the event's count; a step past
 * the first or last tab clears it and leaves the selection.
 */
final class TabRowWheel {
  /** The count that selects one tab. */
  static final int STEP = 3;

  private final CTabFolder tabs;
  private final TabList tabList;
  private int total;

  /**
   * Adds the filter to the folder's display; it is removed with the folder.
   *
   * @param tabs the folder
   * @param tabList selects the tabs
   */
  TabRowWheel(CTabFolder tabs, TabList tabList) {
    this.tabs = tabs;
    this.tabList = tabList;
    final Display display = tabs.getDisplay();
    final Listener filter =
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            filter(event);
          }
        };
    display.addFilter(SWT.MouseVerticalWheel, filter);
    tabs.addListener(
        SWT.Dispose,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            display.removeFilter(SWT.MouseVerticalWheel, filter);
          }
        });
  }

  private void filter(Event event) {
    if (event.count == 0 || tabs.isDisposed() || !overRow(event)) {
      return;
    }
    event.type = SWT.None;
    event.doit = false;
    turn(event.count);
  }

  /** Reports whether the event's position lies over the row of the folder's own shell. */
  private boolean overRow(Event event) {
    if (!(event.widget instanceof Control over)
        || over.isDisposed()
        || over.getShell() != tabs.getShell()
        || !tabs.isVisible()) {
      return false;
    }
    Point point = tabs.toControl(over.toDisplay(event.x, event.y));
    Rectangle client = tabs.getClientArea();
    return point.x >= 0 && point.x < tabs.getSize().x && point.y >= 0 && point.y < client.y;
  }

  /**
   * Adds a wheel count and selects one tab per {@link #STEP} gathered.
   *
   * @param count the event's count, positive toward the previous tab
   */
  void turn(int count) {
    total = Integer.signum(count) == -Integer.signum(total) ? count : total + count;
    while (Math.abs(total) >= STEP) {
      if (!tabList.step(total < 0 ? 1 : -1)) {
        total = 0;
        return;
      }
      total -= Integer.signum(total) * STEP;
    }
  }
}
