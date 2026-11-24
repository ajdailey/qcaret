package io.github.ajdailey.qcaret.ui.swt.platform;

import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;

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

  /** Control data key on a text field: its {@link Hint}. */
  String HINT = ChromeColors.class.getName() + ".hint";

  /**
   * Display data key: {@code Boolean.TRUE} while native controls are set to the dark theme, from
   * the start and after each live switch on win32 and cocoa.
   */
  String NATIVE_DARK = ChromeColors.class.getName() + ".nativeDark";

  /**
   * Reports the native controls' setting, as {@link #NATIVE_DARK} records it.
   *
   * @param display the Display
   * @return true while the dark native theme is set
   */
  static boolean nativeDark(Display display) {
    return Boolean.TRUE.equals(display.getData(NATIVE_DARK));
  }

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

  /** A text field's hint drawn by the application instead of the native message. */
  interface Hint {
    /**
     * Sets the color the hint is drawn in.
     *
     * @param color the color, or null for the native message
     */
    void setColor(Color color);
  }
}
