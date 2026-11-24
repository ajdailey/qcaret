package io.github.ajdailey.qcaret.ui.swt;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Text;

/**
 * Sends a field's Paint listeners over an image, so a test reads what they draw on any platform.
 */
final class TextPaint {
  private TextPaint() {}

  /**
   * Sends a Paint event over an image of the field's client area filled with a color.
   *
   * @param text the field
   * @param own the fill, left where the listeners draw nothing
   * @return the image's pixels
   */
  static ImageData paint(Text text, RGB own) {
    Rectangle area = text.getClientArea();
    Image image = new Image(text.getDisplay(), Math.max(1, area.width), Math.max(1, area.height));
    GC gc = new GC(image);
    try {
      gc.setBackground(new Color(own));
      gc.fillRectangle(0, 0, area.width, area.height);
      Event event = new Event();
      event.gc = gc;
      event.setBounds(new Rectangle(0, 0, area.width, area.height));
      text.notifyListeners(SWT.Paint, event);
    } finally {
      gc.dispose();
    }
    try {
      return image.getImageData();
    } finally {
      image.dispose();
    }
  }

  /**
   * Sends a Paint event over an image, as {@link #paint}, and returns the GC's foreground after the
   * listeners: the ink they drew in. Glyph pixels are no measure of it, since font smoothing tints
   * every glyph pixel (ClearType at 150 %, OBERON G1).
   *
   * @param text the field
   * @param own the fill and the starting foreground, returned where the listeners set no ink
   * @return the foreground
   */
  static RGB ink(Text text, RGB own) {
    Rectangle area = text.getClientArea();
    Image image = new Image(text.getDisplay(), Math.max(1, area.width), Math.max(1, area.height));
    GC gc = new GC(image);
    try {
      gc.setBackground(new Color(own));
      gc.setForeground(new Color(own));
      gc.fillRectangle(0, 0, area.width, area.height);
      Event event = new Event();
      event.gc = gc;
      event.setBounds(new Rectangle(0, 0, area.width, area.height));
      text.notifyListeners(SWT.Paint, event);
      return gc.getForeground().getRGB();
    } finally {
      gc.dispose();
      image.dispose();
    }
  }

  /** Returns a pixel's color. */
  static RGB at(ImageData data, int x, int y) {
    return data.palette.getRGB(data.getPixel(x, y));
  }

  /**
   * Returns the pixel farthest from a ground color by channel sum: the glyph pixel nearest the ink,
   * since font smoothing tints glyph edges.
   *
   * @param data the pixels
   * @param ground the background color
   * @return the pixel
   */
  static RGB farthest(ImageData data, RGB ground) {
    RGB best = ground;
    int bestDistance = 0;
    for (int y = 0; y < data.height; y++) {
      for (int x = 0; x < data.width; x++) {
        RGB rgb = at(data, x, y);
        int distance =
            Math.abs(rgb.red - ground.red)
                + Math.abs(rgb.green - ground.green)
                + Math.abs(rgb.blue - ground.blue);
        if (distance > bestDistance) {
          best = rgb;
          bestDistance = distance;
        }
      }
    }
    return best;
  }
}
