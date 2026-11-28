package io.github.ajdailey.qcaret.ui.swt;

import org.eclipse.swt.SWT;
import org.eclipse.swt.accessibility.ACC;
import org.eclipse.swt.accessibility.AccessibleAdapter;
import org.eclipse.swt.accessibility.AccessibleControlAdapter;
import org.eclipse.swt.accessibility.AccessibleControlEvent;
import org.eclipse.swt.accessibility.AccessibleEvent;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;

/**
 * A status-bar text field painted in one buffered draw, so its background and its text reach the
 * window together. The text shows as given; {@code &} is not a mnemonic. Confined to the UI thread.
 *
 * <p>Do not replace it with a {@link org.eclipse.swt.widgets.Label}: a win32 label fills its
 * background and then draws its text as the text is set, and a frame composed between the two
 * showed the field empty.
 */
final class StatusField extends Canvas {
  private String text = "";
  private String measured;
  private Point extent;

  /**
   * Creates an empty field.
   *
   * @param parent the status bar
   */
  StatusField(Composite parent) {
    super(parent, SWT.DOUBLE_BUFFERED | SWT.NO_FOCUS);
    addListener(
        SWT.Paint,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            paint(event.gc);
          }
        });
    getAccessible()
        .addAccessibleListener(
            new AccessibleAdapter() {
              @Override
              public void getName(AccessibleEvent event) {
                event.result = text;
              }
            });
    getAccessible()
        .addAccessibleControlListener(
            new AccessibleControlAdapter() {
              @Override
              public void getRole(AccessibleControlEvent event) {
                event.detail = ACC.ROLE_LABEL;
              }
            });
  }

  /** Returns the text. */
  String getText() {
    checkWidget();
    return text;
  }

  /**
   * Sets the text and schedules a paint; {@link #update} paints it now.
   *
   * @param value the text
   */
  void setText(String value) {
    checkWidget();
    if (!value.equals(text)) {
      text = value;
      redraw();
    }
  }

  /**
   * Returns the size a text takes in this field's font.
   *
   * @param value the text
   * @return its width and height in points
   */
  Point textSize(String value) {
    checkWidget();
    if (!value.equals(measured)) {
      GC gc = new GC(this);
      try {
        extent = gc.textExtent(value, 0);
      } finally {
        gc.dispose();
      }
      measured = value;
    }
    return new Point(extent.x, extent.y);
  }

  @Override
  public Point computeSize(int widthHint, int heightHint, boolean changed) {
    checkWidget();
    Point size = textSize(text);
    return new Point(
        widthHint == SWT.DEFAULT ? size.x : widthHint,
        heightHint == SWT.DEFAULT ? size.y : heightHint);
  }

  @Override
  public void setFont(Font font) {
    super.setFont(font);
    measured = null;
  }

  /** Draws the text over the background SWT drew into the buffer. */
  private void paint(GC gc) {
    gc.setForeground(getForeground());
    int y = (getClientArea().height - textSize(text).y) / 2;
    gc.drawText(text, 0, Math.max(0, y), SWT.DRAW_TRANSPARENT);
  }
}
