package io.github.ajdailey.qcaret.ui.swt;

import io.github.ajdailey.qcaret.ui.swt.platform.ChromeColors;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Text;

/**
 * A text field's hint. Without a color it is the native message ({@link Text#setMessage}). With a
 * color, the native message is empty and a Paint listener draws the hint in that color while the
 * field is empty and not focused, at the text's origin inside the trim. Windows draws the native
 * message in the theme's gray whatever the field's colors, 87,87,87 on {@code Palette.DARK}'s
 * background. The hint is the field's {@link ChromeColors#HINT} data; only win32's {@link
 * ChromeColors} set a color, and a GTK 3 entry does not show the Paint listener's drawing
 * (measured).
 */
final class FieldHint implements ChromeColors.Hint {
  private final Text field;
  private String hint = "";
  private Color color;

  /**
   * Attaches a hint to a field.
   *
   * @param field the field
   */
  FieldHint(Text field) {
    this.field = field;
    field.setData(ChromeColors.HINT, this);
    field.addListener(
        SWT.Paint,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            paint(event.gc);
          }
        });
    Listener redraw =
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            if (color != null) {
              FieldHint.this.field.redraw();
            }
          }
        };
    field.addListener(SWT.FocusIn, redraw);
    field.addListener(SWT.FocusOut, redraw);
    field.addListener(SWT.Modify, redraw);
  }

  /**
   * Returns the field.
   *
   * @return the field
   */
  Text field() {
    return field;
  }

  /**
   * Sets the hint.
   *
   * @param text the hint, not null
   */
  void setHint(String text) {
    hint = text;
    show();
  }

  /**
   * Sets the color the hint is drawn in.
   *
   * @param drawn the color, or null for the native message
   */
  @Override
  public void setColor(Color drawn) {
    color = drawn;
    show();
  }

  /**
   * Returns the color the hint is drawn in.
   *
   * @return the color, or null when the native message shows it
   */
  Color color() {
    return color;
  }

  private void show() {
    if (field.isDisposed()) {
      return;
    }
    field.setMessage(color == null ? hint : "");
    field.redraw();
  }

  private void paint(GC gc) {
    if (color == null || hint.isEmpty() || field.getCharCount() > 0 || field.isFocusControl()) {
      return;
    }
    Rectangle trim = field.computeTrim(0, 0, 0, 0);
    int border = field.getBorderWidth();
    gc.setFont(field.getFont());
    gc.setForeground(color);
    gc.drawText(hint, -trim.x - border, -trim.y - border, true);
  }
}
