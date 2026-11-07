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
 * (QLogg CORE-4), counted only where it lies in a range, whatever the scan chunk; a stack overflow
 * naming its line and keeping the hits before it (QLogg CORE-2).
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

  /** Asserts the skipped-line count on a string and on a rope, with no limit to end the count. */
  private static void assertSkipped(int expected, String text, int[] ranges, String name) {
    assertEquals(
        expected,
        TextSearch.search(literal("a"), text, ranges, Integer.MAX_VALUE, 20, LIVE).skippedLines(),
        name + " string");
    assertEquals(
        expected,
        TextSearch.search(
                literal("a"), RopeStorage.of(text).snapshot(), ranges, Integer.MAX_VALUE, 20, LIVE)
            .skippedLines(),
        name + " rope");
  }

  @Test
  void aLongLineCountsWhenItLiesInARange() {
    // Long lines (over 20 chars) start at 8 and 66.
    String text = "a short\n" + "x".repeat(50) + "\nthen a\n" + "x".repeat(30) + "\nzz a";
    assertSkipped(0, text, new int[] {0, 5}, "ends before");
    assertSkipped(0, text, new int[] {0, 8}, "ends at its start");
    assertSkipped(1, text, new int[] {0, 9}, "ends inside");
    assertSkipped(1, text, new int[] {0, 62}, "ends after");
    assertSkipped(1, text, new int[] {20, 30}, "starts inside");
    assertSkipped(1, text, new int[] {58, 62}, "starts at its break");
    assertSkipped(1, text, new int[] {10, 20, 30, 40}, "two ranges in one line");
    assertSkipped(2, text, new int[] {10, 20, 70, 71}, "two ranges, two lines");
    assertSkipped(1, text, new int[] {0, 5, 59, 68}, "a second range ending inside");
    assertSkipped(2, text, null, "no ranges");
  }

  @Test
  void theCountDoesNotDependOnTheScanChunk() {
    int chunk = TextSearch.SCAN_CHARS;
    StringBuilder text = new StringBuilder();
    while (text.length() < chunk - 40) {
      text.append("short a\n");
    }
    int first = text.length();
    text.append("x".repeat(100)).append('\n');
    while (text.length() < chunk + 200) {
      text.append("short a\n");
    }
    int second = text.length();
    text.append("x".repeat(100)).append('\n');
    while (text.length() < 3 * chunk) {
      text.append("short a\n");
    }
    String all = text.toString();
    assertTrue(first < chunk && first + 20 < chunk && second < 2 * chunk);
    assertSkipped(0, all, new int[] {0, first - 3}, "ends before, chunk crossed by the line");
    assertSkipped(1, all, new int[] {0, first + 1}, "ends inside");
    assertSkipped(1, all, new int[] {0, chunk + 10}, "ends after the chunk end");
    assertSkipped(1, all, new int[] {0, second}, "ends at the second line");
    assertSkipped(2, all, new int[] {0, second + 1}, "ends inside the second line");
    assertSkipped(2, all, null, "no ranges");
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
