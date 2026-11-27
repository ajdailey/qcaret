package io.github.ajdailey.qcaret.ui.swt.platform;

import org.eclipse.swt.widgets.Control;

/**
 * Colors of a main window's own surfaces under the theme, one implementation per platform: {@link
 * Win32ChromeColors} paints the surfaces, their text fields and trees, and sets the checked tool
 * item ink; {@link NativeChromeColors} leaves them to the native theme. A window takes its instance
 * from {@link PlatformTraits#chromeColors} once and calls every method on every platform. Confined
 * to the UI thread.
 */
public interface ChromeColors {
  /**
   * Control data key: set on a control that paints itself from the palette; the chrome and dialog
   * colors leave it and its children alone.
   */
  String PALETTE_COLORED = "qcaret.paletteColored";

  /**
   * Colors a surface and its controls for the current side, and again at each {@link #apply}, until
   * the surface is disposed.
   *
   * @param surface the surface
   */
  void add(Control surface);

  /**
   * Re-inks an icon set after each {@link #apply} and sets its disabled and checked items' ink.
   *
   * @param set the icons, inked from a tool bar under a surface
   */
  void add(Icons set);

  /**
   * Colors every surface for a side of the theme, then re-inks the icon sets.
   *
   * @param dark true when {@code ui.theme} resolves to dark
   */
  void apply(boolean dark);

  /** Stops coloring new controls; call when the window is disposed. */
  void dispose();

  /** An icon set on a tool bar under a surface. */
  interface Icons {
    /** Reads the ink source's background again and takes the ink it calls for. */
    void reink();

    /**
     * Gives disabled items a faded copy of the icon, or the platform's disabled image.
     *
     * @param on true for the faded copies
     */
    void fadeDisabled(boolean on);

    /**
     * Sets whether a checked item takes the light ink when the set does.
     *
     * @param on false to draw checked items in the dark ink whatever the row's ink
     */
    void lightChecked(boolean on);
  }
}
