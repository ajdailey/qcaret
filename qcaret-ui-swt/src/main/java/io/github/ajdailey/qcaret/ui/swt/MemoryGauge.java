package io.github.ajdailey.qcaret.ui.swt;

import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.view.MemoryFields;
import io.github.ajdailey.qcaret.ui.swt.platform.AccessibleName;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;

/**
 * The status bar's memory indicator: {@link MemoryFields#text} over a bar filled to {@link
 * MemoryFields#fill}, the tip as its tool tip, the text in its {@link AccessibleName}, and a left
 * click that runs an action. The ink is the canvas's own foreground, read at each paint and moved
 * toward white or black until it reaches 4.5 contrast with the background: after a live appearance
 * or theme change on cocoa the foreground SWT reports can match the background. The bar mixes the
 * ink into the background. The width only grows, so the text does not shift the fields beside it on
 * each refresh. Hidden, it takes no room.
 */
final class MemoryGauge {
  /** Pixels between the text and the frame on each side. */
  private static final int PAD = 6;

  /** Percent of the foreground in the filled part and in the frame. */
  private static final int FILL_PERCENT = 30;

  private static final int FRAME_PERCENT = 55;

  private final Canvas canvas;
  private final GridData data;
  private final AccessibleName name;
  private String text = "";
  private double fill;

  /**
   * Creates the indicator, hidden, as the last field of a status bar.
   *
   * @param bar the status bar, laid out by a {@code GridLayout}
   * @param click runs on a left click
   */
  MemoryGauge(Composite bar, final Runnable click) {
    canvas = new Canvas(bar, SWT.DOUBLE_BUFFERED);
    data = new GridData(SWT.END, SWT.CENTER, false, false);
    GC gc = new GC(canvas);
    try {
      data.heightHint = gc.getFontMetrics().getHeight() + 2;
    } finally {
      gc.dispose();
    }
    data.widthHint = 0;
    data.exclude = true;
    canvas.setLayoutData(data);
    canvas.setVisible(false);
    canvas.addListener(
        SWT.Paint,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            paint(event.gc);
          }
        });
    canvas.addListener(
        SWT.Settings,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            canvas.redraw();
          }
        });
    canvas.addListener(
        SWT.MouseUp,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            if (event.button == 1 && canvas.getClientArea().contains(event.x, event.y)) {
              click.run();
            }
          }
        });
    name = AccessibleName.attach(canvas, accessibleName(""));
  }

  private static String accessibleName(String text) {
    return UiText.ACCESSIBLE_MEMORY.format(text).trim();
  }

  /**
   * Shows or hides the indicator.
   *
   * @param shown true to show it
   * @return true when the bar needs a layout
   */
  boolean setShown(boolean shown) {
    if (canvas.isDisposed() || canvas.getVisible() == shown) {
      return false;
    }
    canvas.setVisible(shown);
    data.exclude = !shown;
    return true;
  }

  /**
   * Shows figures, repainting only when the text or the fill changed.
   *
   * @param fields the figures
   * @return true when the indicator widened and the bar needs a layout
   */
  boolean show(MemoryFields fields) {
    if (canvas.isDisposed()) {
      return false;
    }
    if (!fields.tip().equals(canvas.getToolTipText())) {
      canvas.setToolTipText(fields.tip());
    }
    double share = Math.max(0, Math.min(1, fields.fill()));
    if (fields.text().equals(text) && Double.compare(share, fill) == 0) {
      return false;
    }
    text = fields.text();
    name.set(accessibleName(text));
    fill = share;
    canvas.redraw();
    GC gc = new GC(canvas);
    int width;
    try {
      width = gc.textExtent(text).x + 2 * PAD;
    } finally {
      gc.dispose();
    }
    if (width <= data.widthHint) {
      return false;
    }
    data.widthHint = width;
    return true;
  }

  private void paint(GC gc) {
    Rectangle area = canvas.getClientArea();
    RGB ground = canvas.getBackground().getRGB();
    RGB ink = SwtThemeService.readable(canvas.getForeground().getRGB(), ground);
    gc.setBackground(canvas.getBackground());
    gc.fillRectangle(area);
    gc.setBackground(new Color(mix(ground, ink, FILL_PERCENT)));
    gc.fillRectangle(area.x + 1, area.y + 1, filledWidth(area.width - 2), area.height - 2);
    gc.setForeground(new Color(mix(ground, ink, FRAME_PERCENT)));
    gc.drawRectangle(area.x, area.y, area.width - 1, area.height - 1);
    gc.setForeground(new Color(ink));
    Point extent = gc.textExtent(text);
    gc.drawText(
        text, area.x + (area.width - extent.x) / 2, area.y + (area.height - extent.y) / 2, true);
  }

  /** Returns the filled pixels of a bar this wide. */
  int filledWidth(int width) {
    return (int) Math.round(Math.max(0, width) * fill);
  }

  /** Returns {@code from} moved {@code percent} of the way to {@code to}. */
  static RGB mix(RGB from, RGB to, int percent) {
    return new RGB(
        from.red + (to.red - from.red) * percent / 100,
        from.green + (to.green - from.green) * percent / 100,
        from.blue + (to.blue - from.blue) * percent / 100);
  }

  /**
   * Returns the canvas.
   *
   * @return the canvas
   */
  Canvas control() {
    return canvas;
  }

  /**
   * Returns the text as painted.
   *
   * @return the text, empty before the first figures
   */
  String text() {
    return text;
  }
}
