package io.github.ajdailey.qcaret.app.theme;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/** The default palettes are complete and readable (WCAG 2 contrast ratios). */
class PaletteTest {
  private static double luminance(Rgb rgb) {
    return 0.2126 * channel(rgb.red())
        + 0.7152 * channel(rgb.green())
        + 0.0722 * channel(rgb.blue());
  }

  private static double channel(int value) {
    double c = value / 255.0;
    return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
  }

  private static double contrast(Rgb a, Rgb b) {
    double la = luminance(a);
    double lb = luminance(b);
    return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
  }

  @Test
  void tokensAndTextAreReadableOnEveryBackground() {
    for (Palette palette : new Palette[] {Palette.LIGHT, Palette.DARK}) {
      for (Rgb back :
          new Rgb[] {
            palette.background(),
            palette.currentLine(),
            palette.findMatch(),
            palette.occurrence(),
            palette.bracket()
          }) {
        assertTrue(contrast(palette.text(), back) >= 4.5, "text on " + back);
        for (TokenRole role : TokenRole.values()) {
          double ratio = contrast(palette.style(role).foreground(), back);
          assertTrue(
              ratio >= 4.5, role + " on " + back + " in dark " + palette.dark() + ": " + ratio);
        }
      }
      assertTrue(contrast(palette.selectionText(), palette.selection()) >= 4.5);
      assertTrue(contrast(palette.caret(), palette.background()) >= 3);
      assertTrue(contrast(palette.lineNumbers(), palette.gutter()) >= 3);
      assertTrue(contrast(palette.bookmarkComment(), palette.gutter()) >= 3);
    }
  }

  @Test
  void theDarkPaletteIsBrandNavyWithAnAmberCaret() {
    assertEquals(Rgb.of(0x1b2e44), Palette.DARK.background());
    assertEquals(Rgb.of(0xf3b65b), Palette.DARK.caret());
    assertEquals(Rgb.of(0x56c1f0), Palette.DARK.style(TokenRole.KEYWORD).foreground());
    assertEquals(Rgb.of(0x1b2e44), Palette.LIGHT.text());
  }

  @Test
  void aPaletteNeedsEveryRole() {
    final Map<TokenRole, TokenStyle> tokens =
        new EnumMap<TokenRole, TokenStyle>(Palette.LIGHT.tokens());
    tokens.remove(TokenRole.LINK);
    final Rgb c = Rgb.of(0);
    assertThrows(
        IllegalArgumentException.class,
        new Executable() {
          @Override
          public void execute() {
            new Palette(false, c, c, c, c, c, c, c, c, c, c, c, c, tokens);
          }
        });
  }
}
