package io.github.ajdailey.qcaret.ui.swt.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.theme.EditorSchemes;
import io.github.ajdailey.qcaret.app.theme.Palette;
import io.github.ajdailey.qcaret.app.theme.Rgb;
import io.github.ajdailey.qcaret.app.theme.ThemeMode;
import io.github.ajdailey.qcaret.core.text.ControlChars;
import java.util.function.IntFunction;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.junit.jupiter.api.Test;

/**
 * Splitting a row's styles around its runs of boxed chars, and the box color, without a display.
 */
class ControlBoxesTest {
  private static final BoxWidths CELL = new BoxWidths(16, 100, 10, 3);

  private static StyleRange style(int start, int length, Color background) {
    StyleRange style = new StyleRange();
    style.start = start;
    style.length = length;
    style.background = background;
    return style;
  }

  /** Returns each style as {@code start+length}, a box followed by its digits. */
  private static String layout(StyleRange[] styles) {
    StringBuilder out = new StringBuilder();
    for (StyleRange s : styles) {
      out.append(s.start).append('+').append(s.length);
      if (s.metrics != null) {
        out.append(':');
        String run = (String) s.data;
        for (int i = 0; i < run.length(); i++) {
          out.append(ControlChars.label(run.charAt(i)));
        }
      }
      out.append(' ');
    }
    return out.toString().trim();
  }

  @Test
  void aControlInsideARunSplitsItAndKeepsTheRunsBackground() {
    Color back = new Color(1, 2, 3);
    StyleRange[] split =
        ControlBoxes.split(new StyleRange[] {style(100, 5, back)}, 100, "ab\u001bcd", CELL);
    assertEquals("100+2 102+1:1B 103+2", layout(split));
    for (StyleRange s : split) {
      assertSame(back, s.background);
    }
    assertSame(CELL.narrow(), split[1].metrics);
  }

  @Test
  void controlsOutsideEveryStyleGetPlainBoxesAndARunIsOneStyle() {
    StyleRange[] split =
        ControlBoxes.split(new StyleRange[] {style(11, 1, null)}, 10, "\u0001a\u0081\u0082", CELL);
    assertEquals("10+1:01 11+1 12+2:8182", layout(split));
    assertNull(split[0].background);
  }

  @Test
  void aRunCrossingAStyleEdgeSplitsThere() {
    Color back = new Color(1, 2, 3);
    StyleRange[] split =
        ControlBoxes.split(
            new StyleRange[] {style(0, 2, back), style(2, 2, null)}, 0, "a\u007f\u0000b", CELL);
    assertEquals("0+1 1+1:7F 2+1:00 3+1", layout(split));
    assertSame(back, split[1].background);
    assertNull(split[2].background);
  }

  /**
   * Win32 at 150%: two 11 px columns are 14.67 pt, and whole points lay out at 21 or 23 px. The
   * text after each box or run of boxes lands within 1 px of its column; a fixed 15 pt box drifted
   * 1 px per box.
   */
  @Test
  void textAfterEachBoxStaysWithinAPixelOfItsColumnAtAFractionalScale() {
    assertBoxesTrack(new BoxWidths(2 * 11 / 1.5, 150, 10, 3), 22.0);
    assertBoxesTrack(new BoxWidths(2 * 7.224, 100, 10, 3), 2 * 7.224);
    assertBoxesTrack(new BoxWidths(2 * 9.0 / 1.25, 125, 10, 3), 18.0);
  }

  /** Splits rows of boxes, alone and in a run, and checks where the text after each run starts. */
  private static void assertBoxesTrack(BoxWidths widths, double cellPixels) {
    String[] rows = {
      "a\u0001b\u0002c\u0003d\u0004e\u0005f\u0006g", "\u0001\u0002\u0003\u0004\u0005\u0006\u0007x"
    };
    for (String row : rows) {
      StyleRange[] split = ControlBoxes.split(null, 0, row, widths);
      int pixels = 0;
      int boxes = 0;
      for (StyleRange style : split) {
        pixels += widths.pixels(style.metrics.width) * style.length;
        boxes += style.length;
        int end = style.start + style.length;
        if (end == row.length() || !ControlChars.boxed(row.charAt(end))) {
          assertTrue(
              Math.abs(pixels - boxes * cellPixels) <= 1.0,
              row + ": text after box " + boxes + " at " + pixels + " for " + boxes * cellPixels);
        }
      }
      assertEquals(row.equals(rows[0]) ? 6 : 7, boxes);
    }
  }

  @Test
  void theInkIsAtLeastThreeToOneOnEveryGroundOfEveryScheme() {
    for (String name : EditorSchemes.names()) {
      for (ThemeMode mode : ThemeMode.values()) {
        Palette palette = EditorSchemes.palette(name, mode, false);
        Rgb[] grounds = {
          palette.background(),
          palette.currentLine(),
          palette.findMatch(),
          palette.occurrence(),
          palette.bracket()
        };
        for (Rgb ground : grounds) {
          Rgb ink = ControlBoxes.ink(palette.text(), ground);
          assertTrue(ControlBoxes.contrast(ink, ground) >= 3.0, name + " " + ground);
        }
        Rgb selected = ControlBoxes.ink(palette.selectionText(), palette.selection());
        assertTrue(ControlBoxes.contrast(selected, palette.selection()) >= 3.0, name);
      }
    }
  }

  @Test
  void theDefaultPalettesBlendAtTheAlphaToTheMeasuredRatios() {
    Rgb light = ControlBoxes.ink(Palette.LIGHT.text(), Palette.LIGHT.background());
    Rgb dark = ControlBoxes.ink(Palette.DARK.text(), Palette.DARK.background());
    assertEquals(Rgb.of(0x6b7785), light);
    assertEquals(Rgb.of(0x99a6b4), dark);
    assertEquals(4.56, ControlBoxes.contrast(light, Palette.LIGHT.background()), 0.01);
    assertEquals(5.57, ControlBoxes.contrast(dark, Palette.DARK.background()), 0.01);
  }

  @Test
  void aBlendUnderThreeToOneFallsBackToTheTextColor() {
    Rgb text = Rgb.of(0x767676);
    assertEquals(text, ControlBoxes.ink(text, Rgb.of(0xffffff)));
  }

  @Test
  void noStylesGivesOneBoxPerRun() {
    assertEquals("0+2:7F00", layout(ControlBoxes.split(null, 0, "\u007f\u0000", CELL)));
  }

  /**
   * The print label at 72, 300 and 600 dpi, for a 10 pt font 12 pt high whose label is 1.2 times
   * the height wide and high with a fifth of it descent: the part above the descent, where digits
   * are drawn, sits inside the outline with a rule of white on every side.
   */
  @Test
  void thePrintLabelFitsInsideTheOutlineWithARuleOfMargin() {
    int[] dpis = {72, 300, 600};
    int[] heights = {5, 6, 6};
    for (int i = 0; i < dpis.length; i++) {
      final double scale = dpis[i] / 72.0;
      int rule = Math.max(1, Math.round(dpis[i] * 75 / 7200f));
      int row = (int) Math.round(12 * scale);
      int cell = (int) Math.round(12 * scale);
      Rectangle area = ControlBoxes.labelArea(40, 100, cell, row, rule);
      IntFunction<Point> widest =
          new IntFunction<Point>() {
            @Override
            public Point apply(int points) {
              int side = (int) Math.ceil(1.2 * points * scale);
              return new Point(side, side);
            }
          };
      int points = ControlBoxes.labelHeight(10, new Point(area.width, area.height), widest);
      assertEquals(heights[i], points, "dpi " + dpis[i]);
      Point label = widest.apply(points);
      int innerLeft = 40 + rule;
      int innerTop = 100 + 2 * rule;
      int innerRight = 40 + cell - rule;
      int innerBottom = 100 + row - 2 * rule;
      int above = label.y - label.y / 5;
      Point origin = ControlBoxes.labelOrigin(area, label, label.y / 5);
      int x = origin.x;
      int y = origin.y;
      assertTrue(x - innerLeft >= rule, "left at " + dpis[i]);
      assertTrue(y - innerTop >= rule, "top at " + dpis[i]);
      assertTrue(innerRight - (x + label.x) >= rule, "right at " + dpis[i]);
      assertTrue(innerBottom - (y + above) >= rule, "bottom at " + dpis[i]);
    }
  }

  @Test
  void aLabelTooLargeForAnyHeightTakesOnePoint() {
    IntFunction<Point> huge =
        new IntFunction<Point>() {
          @Override
          public Point apply(int points) {
            return new Point(1000, 1000);
          }
        };
    assertEquals(1, ControlBoxes.labelHeight(10, new Point(5, 5), huge));
  }
}
