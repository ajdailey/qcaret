package io.github.ajdailey.qcaret.app.config;

/**
 * The main window's normal (unmaximized) placement and whether it is maximized.
 *
 * @param x left edge in display coordinates, or null when unknown
 * @param y top edge in display coordinates, or null when unknown
 * @param width client-area width in pixels, or null when unknown
 * @param height client-area height in pixels, or null when unknown
 * @param maximized true when the window is maximized
 */
public record WindowState(Integer x, Integer y, Integer width, Integer height, boolean maximized) {
  /** Nothing remembered. */
  public static final WindowState UNKNOWN = new WindowState(null, null, null, null, false);
}
