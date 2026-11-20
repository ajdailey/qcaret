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
