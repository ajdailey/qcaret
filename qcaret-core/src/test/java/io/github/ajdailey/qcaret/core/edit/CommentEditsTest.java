package io.github.ajdailey.qcaret.core.edit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/** Line and block comment toggles at one and several selections, with the selections they leave. */
class CommentEditsTest {
  private static Edit linesEdit(String marked, String line, String start, String end) {
    Marked m = new Marked(marked);
    return CommentEdits.toggleLines(m.document.text(), m.model.selections(), line, start, end);
  }

  private static String lines(String marked, String line, String start, String end) {
    Marked m = new Marked(marked);
    return m.apply(
            CommentEdits.toggleLines(m.document.text(), m.model.selections(), line, start, end))
        .marked();
  }

  private static String block(String marked) {
    Marked m = new Marked(marked);
    return m.apply(CommentEdits.toggleBlock(m.document.text(), m.model.selections(), "(:", ":)"))
        .marked();
  }

  @Test
  void lineCommentGoesAtTheBlocksSmallestIndentationAndSkipsBlankLines() {
    assertEquals("  [// a\n\n  //   b\n]c", lines("  [a\n\n    b\n]c", "//", null, null));
    assertEquals("// x|\n// y|", lines("x|\ny|", "//", null, null));
  }

  @Test
  void lineCommentIsRemovedWhenEveryNonBlankLineHasIt() {
    assertEquals("  [a\n\n    b\n]c", lines("  // [a\n\n  //   b\n]c", "//", null, null));
    assertEquals("[a\nb]", lines("[//a\n//b]", "//", null, null));
    assertEquals("a|\n//b", lines("//a|\n//b", "//", null, null));
  }

  @Test
  void aBlockWithAnUncommentedLineIsCommentedAgain() {
    assertEquals("// // [a\n// b]", lines("// [a\nb]", "//", null, null));
  }

  @Test
  void withoutALineCommentEachBlockOfLinesIsOneBlockComment() {
    assertEquals("[  {- a\n  b -}]", lines("[  a\n  b]", null, "{-", "-}"));
    assertEquals("  a|", lines("  {- a| -}", null, "{-", "-}"));
    assertNull(linesEdit("a|", null, null, null));
  }

  @Test
  void blockCommentWrapsEachSelectionAndACaretsLine() {
    assertEquals("x [(: ab :)] y", block("x [ab] y"));
    assertEquals("  (: a|b :)\n(: c|*d :)", block("  a|b\nc|*d"));
  }

  @Test
  void blockCommentIsRemovedWhenTheRangeHasBothDelimiters() {
    assertEquals("x [ab] y", block("x [(: ab :)] y"));
    assertEquals("a|b", block("(:a|b:)"));
  }

  @Test
  void blockCommentSkipsARangeOverlappingTheOneBefore() {
    assertEquals("[(: a :)]b|*", block("[a]b|*"));
  }
}
