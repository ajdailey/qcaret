package io.github.ajdailey.qcaret.app.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.theme.Palette;
import io.github.ajdailey.qcaret.app.theme.Rgb;
import io.github.ajdailey.qcaret.app.theme.TokenRole;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/** The page around Markdown HTML: the content policy, the title and the palette's stylesheet. */
class MarkdownPageTest {
  private static String hex(Rgb rgb) {
    return String.format(Locale.ROOT, "#%02x%02x%02x", rgb.red(), rgb.green(), rgb.blue());
  }

  @Test
  void thePolicyAllowsOnlyInlineStylesAndDataImages() {
    assertEquals(
        "default-src 'none'; style-src 'unsafe-inline'; img-src data:", MarkdownPage.POLICY);
    String page = MarkdownPage.page("a<b>&\"", "<p>x</p>\n", Palette.LIGHT);
    int policy = page.indexOf("Content-Security-Policy\" content=\"" + MarkdownPage.POLICY);
    assertTrue(policy > 0 && policy < page.indexOf("<style>"), page);
    assertTrue(page.contains("<title>a&lt;b&gt;&amp;&quot;</title>"), page);
    assertTrue(page.contains("<article class=\"markdown-body\">\n<p>x</p>\n</article>"), page);
    assertFalse(page.contains("<script"));
  }

  @Test
  void theZoomScalesEveryPixelLength() {
    String normal = MarkdownPage.stylesheet(Palette.LIGHT);
    assertEquals(normal, MarkdownPage.stylesheet(Palette.LIGHT, 100));
    String[] at100 = {
      "font-size: 14px;", "max-width: 980px; padding: 16px 24px;", "margin: 24px 0 16px;",
      "th, td { border: 1px solid", "padding: 6px 13px;", "border-radius: 4px; padding: 8px 12px;",
      "border-left: 3px solid"
    };
    String[] at200 = {
      "font-size: 28px;", "max-width: 1960px; padding: 32px 48px;", "margin: 48px 0 32px;",
      "th, td { border: 2px solid", "padding: 12px 26px;",
          "border-radius: 8px; padding: 16px 24px;",
      "border-left: 6px solid"
    };
    String zoomed = MarkdownPage.stylesheet(Palette.LIGHT, 200);
    for (int i = 0; i < at100.length; i++) {
      assertTrue(normal.contains(at100[i]), at100[i]);
      assertTrue(zoomed.contains(at200[i]), at200[i]);
    }
    assertFalse(zoomed.contains(": 1px"), "every length scales");
    String small = MarkdownPage.stylesheet(Palette.DARK, 50);
    assertTrue(small.contains("font-size: 7px;"));
    assertTrue(small.contains("th, td { border: 1px solid"), "a border keeps at least 1 px");
    assertTrue(MarkdownPage.page("", "", Palette.LIGHT, 150).contains("font-size: 21px;"));
    assertTrue(normal.contains("h1 { font-size: 1.75em; }"), "headings follow the body font");
    assertTrue(normal.contains("font-size: 90%;"), "code follows the body font");
  }

  @Test
  void theStylesheetFollowsThePalette() {
    for (Palette palette : new Palette[] {Palette.LIGHT, Palette.DARK}) {
      String css = MarkdownPage.stylesheet(palette);
      assertTrue(css.contains("color-scheme: " + (palette.dark() ? "dark" : "light")), css);
      assertTrue(css.contains("background: " + hex(palette.background())), css);
      assertTrue(css.contains("color: " + hex(palette.text())), css);
      assertTrue(
          css.contains(
              ".tk-keyword { color: " + hex(palette.style(TokenRole.KEYWORD).foreground())),
          css);
      assertFalse(css.contains("url("), "no resource a stylesheet could fetch");
    }
    assertFalse(
        MarkdownPage.stylesheet(Palette.LIGHT).equals(MarkdownPage.stylesheet(Palette.DARK)));
  }
}
