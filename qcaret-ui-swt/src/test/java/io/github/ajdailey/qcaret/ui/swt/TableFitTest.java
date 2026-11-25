package io.github.ajdailey.qcaret.ui.swt;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@link TableFit#shares}: proportional widths that keep each header's minimum while they fit;
 * {@link TableFit#room} at a fractional zoom; {@link TableFit#order}.
 */
class TableFitTest {
  private static final int[] WEIGHTS = {0, 260, 480};

  /** The Bookmarks Line column's width in points. */
  private static final int LINE = 70;

  /** The Comment and Text header minima in points, as OBERON's dark header at 150% gives them. */
  private static final int[] MINIMA = {0, 69, 36};

  @Test
  void aWideTableSharesInProportion() {
    assertArrayEquals(
        new int[] {0, 260, 480}, TableFit.shares(740, WEIGHTS, new int[] {0, 70, 40}));
  }

  @Test
  void aColumnBelowItsHeaderTakesItsMinimumAndTheOtherTheRest() {
    assertArrayEquals(new int[] {0, 70, 80}, TableFit.shares(150, WEIGHTS, new int[] {0, 70, 40}));
  }

  @Test
  void aTableNarrowerThanEveryMinimumSharesInProportion() {
    assertArrayEquals(new int[] {0, 35, 65}, TableFit.shares(100, WEIGHTS, new int[] {0, 70, 40}));
  }

  /**
   * At 125%, 150% and 175% the columns, and a hidden vertical scroll bar, fit the table in pixels
   * on both sides of the switch between proportion and pinned minima. SWT rounds the client width,
   * the bar's width and each column's width between points and pixels on its own. OBERON at 150%: a
   * 290 px table and a 25 px bar give columns 105, 104 and 54 px.
   */
  @Test
  void theColumnsFitInPixelsAroundTheSwitchAtAFractionalZoom() {
    for (int zoom : new int[] {125, 150, 175}) {
      for (int client = 200; client < 700; client++) {
        for (int bar = zoom * 17 / 100 - 1; bar <= zoom * 17 / 100 + 1; bar++) {
          int[] shown = fit(zoom, client - bar, 0);
          assertTrue(shown[0] <= client - bar, zoom + "% bar shown, " + client + " px");
          int[] hidden = fit(zoom, client, bar);
          assertTrue(hidden[0] + bar <= client, zoom + "% bar hidden, " + client + " px");
        }
      }
    }
    assertArrayEquals(new int[] {263, 104, 54}, fit(150, 290, 25));
  }

  /**
   * Returns the columns' pixel total and Comment's and Text's pixel widths for a client area of
   * {@code client} pixels and a hidden bar of {@code bar} pixels.
   */
  private static int[] fit(int zoom, int client, int bar) {
    int width = TableFit.room(points(client, zoom), points(bar, zoom), zoom % 100 != 0);
    int[] widths = TableFit.shares(Math.max(0, width - LINE), WEIGHTS, MINIMA);
    int comment = pixels(widths[1], zoom);
    int text = pixels(widths[2], zoom);
    return new int[] {pixels(LINE, zoom) + comment + text, comment, text};
  }

  private static int points(int pixels, int zoom) {
    return Math.round(pixels * 100f / zoom);
  }

  private static int pixels(int points, int zoom) {
    return Math.round(points * zoom / 100f);
  }

  /**
   * Crossing the switch while widening narrows Text before Comment widens. OBERON at 150%, a 288 px
   * table widened to 290 px: Comment 36 to 69 points, Text 68 to 36. Comment first made the columns
   * 311 px in a 290 px table, and win32 kept both scroll bars after they fit.
   */
  @Test
  void narrowingColumnsComeFirst() {
    assertArrayEquals(
        new int[] {2, 1}, TableFit.order(new int[] {0, 36, 68}, new int[] {0, 69, 36}));
    assertArrayEquals(
        new int[] {1, 2}, TableFit.order(new int[] {0, 69, 36}, new int[] {0, 36, 68}));
    assertArrayEquals(new int[] {2}, TableFit.order(new int[] {0, 69, 40}, new int[] {0, 69, 36}));
    assertArrayEquals(new int[0], TableFit.order(new int[] {0, 69, 36}, new int[] {0, 69, 36}));
  }
}
