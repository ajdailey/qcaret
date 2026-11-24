package io.github.ajdailey.qcaret.ui.swt.platform;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Text;

/**
 * win32: draws an empty, unfocused single-line {@link Text} from {@link SWT#Paint}, after the
 * native paint. The listener fills the damaged area with the field's background and draws its
 * message ({@code setMessage}) in the color an {@link Ink} gives, or leaves the native drawing when
 * that color is null. The native drawing ignores the field's colors in two cases (measured on
 * {@code windows-2025}): a hint draws {@code #6d6d6d} read-only and {@code #575757} editable
 * whatever the background, and under the dark native setting an empty editable field with light
 * colors set draws {@code #212121} while unfocused. Confined to the UI thread.
 */
final class Win32EmptyField {
  /** Hint x in the client area: where win32 draws a read-only field's hint at 100 % zoom. */
  static final int HINT_X = 4;

  /** Hint y in the client area, as {@link #HINT_X}. */
  static final int HINT_Y = 1;

  /** Control data key: set on a field once its Paint listener is installed. */
  private static final String DRAWN = Win32EmptyField.class.getName() + ".drawn";

  /** The hint color of an empty, unfocused field. */
  interface Ink {
    /**
     * Returns the hint color for the field's current colors.
     *
     * @param text the field
     * @return the color, or null to leave the native drawing
     */
    Color hint(Text text);
  }

  private Win32EmptyField() {}

  /**
   * Installs the Paint listener once per field; a multi-line field gets none. Focus changes and
   * modifications repaint the field.
   *
   * @param text the field
   * @param ink the hint color
   */
  static void draw(final Text text, final Ink ink) {
    if ((text.getStyle() & SWT.MULTI) != 0 || text.getData(DRAWN) != null) {
      return;
    }
    text.setData(DRAWN, Boolean.TRUE);
    text.addListener(
        SWT.Paint,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            if (text.getCharCount() > 0 || text.isFocusControl()) {
              return;
            }
            Color hint = ink.hint(text);
            if (hint == null) {
              return;
            }
            Rectangle area = text.getClientArea();
            event.gc.setBackground(text.getBackground());
            event.gc.fillRectangle(event.x, event.y, event.width, event.height);
            if (!text.getMessage().isEmpty()) {
              event.gc.setForeground(hint);
              event.gc.setFont(text.getFont());
              event.gc.drawText(text.getMessage(), area.x + HINT_X, area.y + HINT_Y, true);
            }
          }
        });
    Listener redraw =
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            if (!text.isDisposed()) {
              text.redraw();
            }
          }
        };
    text.addListener(SWT.FocusIn, redraw);
    text.addListener(SWT.FocusOut, redraw);
    text.addListener(SWT.Modify, redraw);
  }
}
