package io.github.ajdailey.qcaret.ui.swt.platform;

import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.widgets.Display;

/**
 * The divider color of win32 table headers with header colors set, through the Display data key
 * {@link #KEY}; without it SWT 3.135.0 draws the dividers in {@code COLOR_3DFACE}, {@code #f0f0f0}.
 * A listed Public SWT API exception: the key names an SWT internal, and ArchUnit {@code
 * headerLineKeyOnlyInWin32HeaderLines} keeps the string in this class. If an SWT upgrade drops the
 * key, the dividers draw light again and {@code DialogGridLinesTest} fails on win32. Confined to
 * the UI thread.
 */
final class Win32HeaderLines {
  /** SWT's win32 Display data key for the header divider color. */
  static final String KEY = "org.eclipse.swt.internal.win32.Table.headerLineColor";

  /**
   * Display data key holding the color {@link #set} last set: the win32 Display consumes {@link
   * #KEY} without storing it, so {@code getData(KEY)} returns null there.
   */
  private static final String SET = Win32HeaderLines.class.getName() + ".set";

  private Win32HeaderLines() {}

  /**
   * Sets the divider color of every table with header colors on a Display.
   *
   * @param display the Display
   * @param color the color, which must outlive its use, or null for {@code COLOR_3DFACE}
   */
  static void set(Display display, Color color) {
    display.setData(KEY, color);
    display.setData(SET, color);
  }

  /**
   * Returns the divider color {@link #set} last set.
   *
   * @param display the Display
   * @return the color, or null
   */
  static Color get(Display display) {
    return (Color) display.getData(SET);
  }
}
