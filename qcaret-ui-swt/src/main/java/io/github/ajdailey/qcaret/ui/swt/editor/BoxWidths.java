package io.github.ajdailey.qcaret.ui.swt.editor;

import org.eclipse.swt.graphics.GlyphMetrics;

/**
 * The widths of a row's {@link ControlBoxes} boxes. {@link GlyphMetrics} takes whole points, which
 * the toolkit lays out at {@code round(points * scale)} pixels, so one fixed width moves the text
 * after each box by the rounding: 1 to 1.5 px per box on win32 at 150%. Each run of boxes takes a
 * mix of a narrow and a wide width, one point apart, that ends the run within 1 px of the column
 * its text would reach.
 */
final class BoxWidths {
  private final double cell;
  private final float scale;
  private final GlyphMetrics narrow;
  private final GlyphMetrics wide;

  /**
   * Creates the widths of a font.
   *
   * @param cell two columns in points, fractional
   * @param zoom the percent the toolkit lays points out at: the zoom on win32, else 100
   * @param ascent the font's ascent
   * @param descent the font's descent
   */
  BoxWidths(double cell, int zoom, int ascent, int descent) {
    this.cell = cell;
    scale = zoom / 100f;
    int points = Math.max(1, (int) Math.floor(cell));
    narrow = new GlyphMetrics(ascent, descent, points);
    wide = new GlyphMetrics(ascent, descent, points + 1);
  }

  /** Returns the narrow width's metrics. */
  GlyphMetrics narrow() {
    return narrow;
  }

  /** Returns the wide width's metrics. */
  GlyphMetrics wide() {
    return wide;
  }

  /**
   * Returns the pixels a width lays out at, rounded as SWT rounds.
   *
   * @param points the width in points
   * @return the pixels
   */
  int pixels(int points) {
    return Math.round(points * scale);
  }

  /**
   * Returns how many boxes of a run take the narrow width; the rest take the wide one.
   *
   * @param before the boxes earlier in the row
   * @param spent the pixels those boxes take
   * @param run the boxes in the run
   * @return 0 to {@code run}
   */
  int narrowCount(int before, int spent, int run) {
    int need = (int) Math.round((before + run) * cell * scale) - spent;
    int small = pixels(narrow.width);
    int large = pixels(wide.width);
    long count = Math.round((run * (double) large - need) / (large - small));
    return (int) Math.max(0, Math.min(run, count));
  }
}
