package io.github.ajdailey.qcaret.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.ajdailey.qcaret.core.buffer.TextSnapshot;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.document.Document;
import io.github.ajdailey.qcaret.core.document.UndoLimits;
import io.github.ajdailey.qcaret.core.markdown.MarkdownRenderer;
import io.github.ajdailey.qcaret.core.markdown.RenderedMarkdown;
import io.github.ajdailey.qcaret.core.text.LineEnding;
import io.github.ajdailey.qcaret.core.text.TextEncoding;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * {@link PreviewMatch}: the rendered line of a match, and its span only where it is unambiguous.
 */
class PreviewMatchTest {
  private static TextSnapshot source(String markdown) {
    return Document.of(
            markdown,
            TextEncoding.of(StandardCharsets.UTF_8, false),
            LineEnding.LF,
            UndoLimits.DEFAULTS)
        .text();
  }

  private static PreviewMatch match(String markdown, String text, int occurrence) {
    int at = -1;
    for (int i = 0; i <= occurrence; i++) {
      at = markdown.indexOf(text, at + 1);
    }
    return PreviewMatch.of(render(markdown), source(markdown), at, at + text.length());
  }

  private static RenderedMarkdown render(String markdown) {
    return new MarkdownRenderer().render(markdown, null, new CancellationToken());
  }

  private static PreviewMatch spanOf(String markdown, int sourceLine, String text) {
    RenderedMarkdown r = render(markdown);
    int line = r.renderedLine(sourceLine);
    int at = r.text().indexOf(text, r.lineStart(line));
    return new PreviewMatch(line, at, at + text.length());
  }

  @Test
  void aMatchInAHeadingOrAParagraphMapsToItsRenderedText() {
    String markdown = "# Mock needle\n\nA **needle** here.\n";
    assertEquals(spanOf(markdown, 0, "needle"), match(markdown, "needle", 0));
    assertEquals(spanOf(markdown, 2, "needle"), match(markdown, "needle", 1));
  }

  @Test
  void aMatchOnAParagraphsLaterLineMapsToTheParagraph() {
    String markdown = "# A\n\none\ntwo needle\n";
    assertEquals(spanOf(markdown, 2, "needle"), match(markdown, "needle", 0));
  }

  @Test
  void aMatchInMarkupThatDoesNotRenderHasOnlyItsLine() {
    String markdown = "# A\n\nSee [docs](needle.md) now.\n";
    assertEquals(
        new PreviewMatch(render(markdown).renderedLine(2), -1, -1), match(markdown, "needle", 0));
  }

  @Test
  void aMatchWhoseTextRepeatsInItsBlockHasOnlyItsLine() {
    String markdown = "needle and [needle](x.md)\n";
    assertEquals(new PreviewMatch(0, -1, -1), match(markdown, "needle", 1));
  }

  @Test
  void aMatchInATableHasOnlyItsLine() {
    String markdown = "| a | b |\n|---|---|\n| needle | x |\n";
    assertEquals(
        new PreviewMatch(render(markdown).renderedLine(2), -1, -1), match(markdown, "needle", 0));
  }

  @Test
  void aBlockOverTheLimitHasOnlyItsLine() {
    String markdown = "x".repeat(PreviewMatch.MAX_CHARS) + " needle\n";
    assertEquals(new PreviewMatch(0, -1, -1), match(markdown, "needle", 0));
  }

  @Test
  void anEmptyMatchHasOnlyItsLine() {
    String markdown = "# A\n\npara\n";
    RenderedMarkdown r = render(markdown);
    assertEquals(
        new PreviewMatch(r.renderedLine(2), -1, -1), PreviewMatch.of(r, source(markdown), 5, 5));
  }
}
