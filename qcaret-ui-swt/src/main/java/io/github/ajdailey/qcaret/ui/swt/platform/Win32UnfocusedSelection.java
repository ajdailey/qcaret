package io.github.ajdailey.qcaret.ui.swt.platform;

import java.util.function.BooleanSupplier;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;

/**
 * Draws the selected rows of a dark {@code Table} or {@code Tree} without focus, on {@link
 * SWT#EraseItem}. The dark native theme draws them {@code #252525} on the {@code #2b2b2b} field,
 * 1.1:1 (measured on Windows 11, OBERON); {@link #BACKGROUND} is 3.03:1 on the field with white
 * text at 4.67:1, and gray, so it stays distinct from the focused {@code #223e55}. A focused list
 * keeps the native selection. Confined to the UI thread.
 */
final class Win32UnfocusedSelection {
  /** The unfocused selected row's background. */
  static final RGB BACKGROUND = new RGB(0x74, 0x74, 0x74);

  /** The unfocused selected row's text. */
  static final RGB TEXT = new RGB(0xff, 0xff, 0xff);

  private static final Color BACKGROUND_COLOR = new Color(BACKGROUND);
  private static final Color TEXT_COLOR = new Color(TEXT);

  /** Control data key: set once a list's listeners are installed. */
  private static final String DRAWN = Win32UnfocusedSelection.class.getName() + ".drawn";

  private Win32UnfocusedSelection() {}

  /**
   * Draws the list's unfocused selection from now on, while {@code dark} holds; a second call does
   * nothing.
   *
   * @param list a {@code Table} or {@code Tree}
   * @param dark true while the list shows the dark colors over the dark native theme
   */
  static void draw(final Composite list, final BooleanSupplier dark) {
    if (list.getData(DRAWN) != null) {
      return;
    }
    list.setData(DRAWN, Boolean.TRUE);
    list.addListener(
        SWT.EraseItem,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            if ((event.detail & SWT.SELECTED) != 0
                && !list.isFocusControl()
                && dark.getAsBoolean()) {
              event.gc.setBackground(BACKGROUND_COLOR);
              event.gc.fillRectangle(event.x, event.y, event.width, event.height);
              event.gc.setForeground(TEXT_COLOR);
              event.detail &= ~(SWT.SELECTED | SWT.HOT);
            }
          }
        });
    Listener redraw =
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            if (!list.isDisposed()) {
              list.redraw();
            }
          }
        };
    list.addListener(SWT.FocusIn, redraw);
    list.addListener(SWT.FocusOut, redraw);
  }
}
