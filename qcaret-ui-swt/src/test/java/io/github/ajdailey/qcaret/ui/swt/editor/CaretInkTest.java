package io.github.ajdailey.qcaret.ui.swt.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.theme.Palette;
import io.github.ajdailey.qcaret.app.theme.Rgb;
import org.eclipse.swt.graphics.RGB;
import org.junit.jupiter.api.Test;

/** The caret image color for each compositing operator, over both built-in palettes. */
class CaretInkTest {
  private static RGB rgb(Rgb value) {
    return new RGB(value.red(), value.green(), value.blue());
  }

  @Test
  void xorAndOverShowTheCaretColorExactly() {
    for (Palette palette : new Palette[] {Palette.LIGHT, Palette.DARK}) {
      RGB line = rgb(palette.currentLine());
      RGB caret = rgb(palette.caret());
      for (CaretInk.Blend blend : new CaretInk.Blend[] {CaretInk.Blend.XOR, CaretInk.Blend.OVER}) {
        assertEquals(caret, CaretInk.shown(line, CaretInk.source(line, caret, blend), blend));
      }
    }
  }

  /** Light amber over a light line is exact; over navy, red 0xf3 cannot show over 0x22. */
  @Test
  void differenceShowsTheNearestReachableColor() {
    CaretInk.Blend blend = CaretInk.Blend.DIFFERENCE;
    RGB light = rgb(Palette.LIGHT.currentLine());
    RGB lightCaret = rgb(Palette.LIGHT.caret());
    assertEquals(
        lightCaret, CaretInk.shown(light, CaretInk.source(light, lightCaret, blend), blend));
    RGB dark = rgb(Palette.DARK.currentLine());
    RGB darkCaret = rgb(Palette.DARK.caret());
    RGB shown = CaretInk.shown(dark, CaretInk.source(dark, darkCaret, blend), blend);
    assertEquals(new RGB(0xff - 0x22, darkCaret.green, darkCaret.blue), shown);
    assertTrue(darkCaret.red - shown.red <= 0x22, shown.toString());
    // Neither sum nor difference fits: 0 shows the background's own value when that is nearer.
    assertEquals(
        new RGB(0, 0, 0), CaretInk.source(new RGB(200, 200, 200), new RGB(220, 220, 220), blend));
  }
}
