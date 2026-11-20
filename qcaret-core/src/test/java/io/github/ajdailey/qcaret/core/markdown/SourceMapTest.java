package io.github.ajdailey.qcaret.core.markdown;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** {@link RenderedMarkdown#sourceLine} and {@link RenderedMarkdown#renderedLine} at block edges. */
class SourceMapTest {
  // Source lines:
  // 0 # Head
  // 1
  // 2 para one
  // 3 para two
  // 4
  // 5 [ref]: https://example.com
  // 6
  // 7 ```
  // 8 code a
  // 9 code b
  // 10 ```
  // 11
  // 12 - item
  private static final String SOURCE =
      "# Head\n\npara one\npara two\n\n[ref]: https://example.com\n\n```\ncode a\ncode b\n```\n\n"
          + "- item\n";

  @Test
  void renderedToSource() {
    RenderedMarkdown r = Render.of(SOURCE);
    // Rendered: 0 Head, 1 sep, 2 para, 3 sep, 4 code a, 5 code b, 6 sep, 7 item
    assertEquals("para one para two", Render.line(r, 2));
    int[] expected = {0, 1, 2, 4, 8, 9, 11, 12};
    for (int i = 0; i < expected.length; i++) {
      assertEquals(expected[i], r.sourceLine(i), "rendered line " + i);
    }
    assertEquals(12, r.sourceLine(99));
    assertEquals(0, r.sourceLine(-1));
  }

  @Test
  void sourceToRendered() {
    RenderedMarkdown r = Render.of(SOURCE);
    int[] expected = {0, 1, 2, 2, 3, 3, 3, 3, 4, 5, 5, 6, 7, 7};
    for (int i = 0; i < expected.length; i++) {
      assertEquals(expected[i], r.renderedLine(i), "source line " + i);
    }
  }

  @Test
  void eachRoundTripReturnsAtOrBeforeTheLine() {
    RenderedMarkdown r = Render.of(SOURCE);
    for (int line = 0; line < r.lineCount(); line++) {
      assertEquals(line, r.renderedLine(r.sourceLine(line)), "rendered line " + line);
    }
  }
}
