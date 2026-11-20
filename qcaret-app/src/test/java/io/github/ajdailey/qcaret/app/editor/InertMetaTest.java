package io.github.ajdailey.qcaret.app.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.github.ajdailey.qcaret.app.theme.Palette;
import org.junit.jupiter.api.Test;

/** Meta tags in a Markdown fragment renamed to {@code <wbr>}, and every other tag kept. */
class InertMetaTest {
  @Test
  void everyMetaOpenerBecomesWbr() {
    assertEquals(
        "<p>a</p><wbr http-equiv=\"refresh\" content=\"0; url=http://127.0.0.1/r\">",
        InertMeta.apply(
            "<p>a</p><meta http-equiv=\"refresh\" content=\"0; url=http://127.0.0.1/r\">"));
    assertEquals("<wbr\thttp-equiv=refresh>", InertMeta.apply("<MeTa\thttp-equiv=refresh>"));
    assertEquals("<wbr\nx><wbr/><wbr>", InertMeta.apply("<meta\nx><meta/><META>"));
    assertEquals("<wbr\fa><wbr\rb>x<wbr", InertMeta.apply("<meta\fa><meta\rb>x<meta"));
  }

  @Test
  void otherTagsAndTextStay() {
    String html = "<metadata><metab>x</metab><p>meta</p><a>&lt;meta x&gt;</a>< meta>";
    assertSame(html, InertMeta.apply(html));
    assertEquals("", InertMeta.apply(""));
    assertEquals("<", InertMeta.apply("<"));
  }

  @Test
  void thePageKeepsItsOwnMetasAndDisarmsTheBodys() {
    String page =
        MarkdownPage.page("", "<meta http-equiv=\"refresh\" content=\"0\">", Palette.LIGHT);
    assertEquals(2, count(page, "<meta "));
    assertFalse(page.contains("<meta http-equiv=\"refresh\""), page);
    assertEquals(1, count(page, "<wbr http-equiv=\"refresh\""));
  }

  private static int count(String text, String part) {
    int found = 0;
    int at = text.indexOf(part);
    while (at >= 0) {
      found++;
      at = text.indexOf(part, at + 1);
    }
    return found;
  }
}
