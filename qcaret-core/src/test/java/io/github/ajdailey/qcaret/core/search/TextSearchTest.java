package io.github.ajdailey.qcaret.core.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.core.buffer.RopeStorage;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Line, column and snippet of each hit over CR, LF and CR LF, on a decoded string and on a rope;
 * selection ranges; a line too long to search skipped and counted with the lines around it searched
 * (QLogg CORE-4); a stack overflow naming its line and keeping the hits before it (QLogg CORE-2).
 */
class TextSearchTest {
  private static final CancellationToken LIVE = new CancellationToken();

  private static TextMatcher literal(String text) {
    return new SearchQuery(text, false, true, false).compile();
  }

  private static List<String> describe(TextSearch.Outcome outcome) {
    List<String> out = new ArrayList<String>();
    for (TextSearch.Hit hit : outcome.hits()) {
      out.add(
          hit.line()
              + ":"
              + hit.column()
              + " "
              + hit.snippet()
              + " ["
              + hit.snippet().substring(hit.snippetStart(), hit.snippetEnd())
              + "]");
    }
    return out;
  }

  @Test
  void hitsCarryLineColumnAndTrimmedSnippetOnStringsAndRopes() {
    String text = "one key\r\n\tkey two\rthree\nkey";
    List<String> expected = List.of("0:4 one key [key]", "1:1 key two [key]", "3:0 key [key]");
    TextSearch.Outcome plain = TextSearch.search(literal("key"), text, null, 100, 1000, LIVE);
    TextSearch.Outcome rope =
        TextSearch.search(literal("key"), RopeStorage.of(text).snapshot(), null, 100, 1000, LIVE);
    assertEquals(expected, describe(plain));
    assertEquals(expected, describe(rope));
    assertEquals("key".hashCode(), plain.hits().get(0).hash());
    assertEquals(4, plain.hits().get(0).start());
    assertEquals(7, plain.hits().get(0).end());
  }

  @Test
  void aLongLineIsCutAroundTheMatch() {
    String line = "x".repeat(2000) + "key" + "y".repeat(2000);
    TextSearch.Hit hit =
        TextSearch.search(literal("key"), line, null, 10, 1_000_000, LIVE).hits().get(0);
    assertEquals(TextSearch.SNIPPET_CHARS, hit.snippet().length());
    assertEquals("key", hit.snippet().substring(hit.snippetStart(), hit.snippetEnd()));
    assertEquals(2000, hit.column());
  }

  @Test
  void rangesKeepMatchesLyingInside() {
    String text = "ab ab ab ab";
    TextSearch.Outcome outcome =
        TextSearch.search(literal("ab"), text, new int[] {0, 4, 6, 11}, 10, 1000, LIVE);
    List<Integer> starts = new ArrayList<Integer>();
    for (TextSearch.Hit hit : outcome.hits()) {
      starts.add(hit.start());
    }
    assertEquals(List.of(0, 6, 9), starts);
  }

  @Test
  void aLineTooLongIsSkippedAndCounted() {
    String text = "a short\n" + "a".repeat(50) + "\nthen a\r\n" + "a".repeat(30) + "\nzz a";
    TextSearch.Outcome outcome = TextSearch.search(literal("a"), text, null, 100, 20, LIVE);
    assertEquals(2, outcome.skippedLines());
    List<Integer> lines = new ArrayList<Integer>();
    for (TextSearch.Hit hit : outcome.hits()) {
      lines.add(hit.line());
    }
    assertEquals(List.of(0, 2, 4), lines);
    TextSearch.Outcome rope =
        TextSearch.search(literal("a"), RopeStorage.of(text).snapshot(), null, 100, 20, LIVE);
    assertEquals(2, rope.skippedLines());
    assertEquals(3, rope.hits().size());
  }

  @Test
  void theLimitCutsAndMarksTheOutcome() {
    TextSearch.Outcome outcome = TextSearch.search(literal("a"), "aaaa", null, 3, 1000, LIVE);
    assertEquals(3, outcome.hits().size());
    assertTrue(outcome.limited());
    assertFalse(TextSearch.search(literal("a"), "aaa", null, 3, 1000, LIVE).limited());
  }

  @Test
  void aStackOverflowNamesItsLineAndKeepsTheHitsBefore() {
    String text = "ac\nbc\n" + "ab".repeat(200_000) + "\nac";
    TextMatcher m = new SearchQuery("(a|b)*c", true, true, false).compile();
    TextSearch.Outcome outcome = TextSearch.search(m, text, null, 100, 1_000_000, LIVE);
    assertEquals(2, outcome.tooComplexLine());
    assertEquals(2, outcome.hits().size());
  }
}
