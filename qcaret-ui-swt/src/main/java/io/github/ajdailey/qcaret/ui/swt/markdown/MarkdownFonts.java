package io.github.ajdailey.qcaret.ui.swt.markdown;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Device;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontData;

/**
 * The fonts of one preview pane, created on first use and disposed together: the body font with
 * bold and italic, the six heading sizes, a smaller size for subscript and superscript, and the
 * monospace family for code, all scaled by the zoom. Call on the UI thread.
 */
final class MarkdownFonts {
  /** Heading sizes as a share of the body size, level 1 first. */
  private static final float[] HEADING = {1.75f, 1.5f, 1.25f, 1.1f, 1f, 0.9f};

  /** Subscript and superscript size as a share of the body size. */
  private static final float SMALL = 0.8f;

  private final Device device;
  private final FontData body;
  private final String mono;
  private Map<String, Font> fonts = new HashMap<String, Font>();
  private int zoom = 100;

  /**
   * Creates the fonts of a pane.
   *
   * @param device the display
   * @param body the body font
   * @param mono the monospace family
   */
  MarkdownFonts(Device device, FontData body, String mono) {
    this.device = device;
    this.body = body;
    this.mono = mono;
  }

  /**
   * Returns a font.
   *
   * @param monospace true for the code family
   * @param style {@link SWT#NORMAL}, {@link SWT#BOLD}, {@link SWT#ITALIC} or both
   * @param level a heading level 1 to 6 for its size, 0 for the body size
   * @param small true for the subscript and superscript size
   * @return the font, disposed by {@link #dispose}
   */
  Font get(boolean monospace, int style, int level, boolean small) {
    float scale = level > 0 ? HEADING[level - 1] : 1f;
    if (small) {
      scale *= SMALL;
    }
    int points = Math.max(1, Math.round(body.getHeight() * scale * zoom / 100f));
    String name = monospace ? mono : body.getName();
    String key = name + "/" + points + "/" + style;
    Font font = fonts.get(key);
    if (font == null) {
      font = new Font(device, name, points, style);
      fonts.put(key, font);
    }
    return font;
  }

  /**
   * Sets the zoom. The fonts created so far stay valid until the caller disposes them, since
   * widgets may still hold them.
   *
   * @param percent the size in percent of the body font's
   * @return the fonts created before
   */
  List<Font> setZoom(int percent) {
    zoom = percent;
    List<Font> before = new ArrayList<Font>(fonts.values());
    fonts = new HashMap<String, Font>();
    return before;
  }

  /**
   * Returns the zoom.
   *
   * @return percent of the body font's size
   */
  int zoom() {
    return zoom;
  }

  /** Disposes every font created. */
  void dispose() {
    for (Font font : fonts.values()) {
      font.dispose();
    }
    fonts.clear();
  }
}
