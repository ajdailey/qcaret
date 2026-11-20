package io.github.ajdailey.qcaret.app.editor;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.ajdailey.qcaret.app.theme.TokenRole;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.markdown.MarkdownRenderer;
import io.github.ajdailey.qcaret.core.markdown.RenderedMarkdown;
import java.util.List;
import org.junit.jupiter.api.Test;

/** {@link MarkdownSpans}: nested runs flatten into disjoint spans, code tokens join them. */
class MarkdownSpansTest {
  private static RenderedMarkdown render(String text) {
    return new MarkdownRenderer().render(text, null, new CancellationToken());
  }

  @Test
  void nestedRunsFlattenAndAdjacentEqualSpansMerge() {
    RenderedMarkdown r = render("a **b *c* d** [e](#x)");
    assertEquals("a b c d e", r.text());
    int[] spans = MarkdownSpans.of(r, List.of());
    int strong = MarkdownSpans.STRONG;
    int both = MarkdownSpans.STRONG | MarkdownSpans.EMPHASIS;
    assertArrayEquals(
        new int[] {2, 4, strong, 4, 5, both, 5, 7, strong, 8, 9, MarkdownSpans.LINK}, spans);
  }

  @Test
  void headingLevelAndCodeTokenRolesAreInTheBits() {
    RenderedMarkdown r = render("## Title\n\n```\nab\n```\n");
    int code = r.lineStart(r.codeBlocks().get(0).firstLine());
    int[] tokens = {code, code + 1, TokenRole.KEYWORD.ordinal()};
    int[] spans = MarkdownSpans.of(r, List.of(tokens));
    assertEquals(2, MarkdownSpans.level(spans[2]));
    assertEquals(-1, MarkdownSpans.role(spans[2]));
    assertEquals(code, spans[3]);
    assertEquals(TokenRole.KEYWORD.ordinal(), MarkdownSpans.role(spans[5]));
    assertEquals(0, MarkdownSpans.level(spans[5]));
  }

  @Test
  void firstFindsTheSpanEndingAfterAnOffset() {
    int[] spans = {2, 4, 1, 6, 8, 2};
    assertEquals(0, MarkdownSpans.first(spans, 0));
    assertEquals(0, MarkdownSpans.first(spans, 3));
    assertEquals(1, MarkdownSpans.first(spans, 4));
    assertEquals(2, MarkdownSpans.first(spans, 8));
  }
}
