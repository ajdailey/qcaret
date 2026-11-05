package io.github.ajdailey.qcaret.core.edit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The word index behind completion, and whole-word occurrences in a range. */
class WordIndexTest {
  private static final CancellationToken TOKEN = new CancellationToken();

  private static WordIndex index(String text) {
    return WordIndex.build(new Marked(text).document.text(), TOKEN);
  }

  @Test
  void wordsStartWithALetterOrUnderscoreAndHaveTwoChars() {
    WordIndex index = index("a bb 9lives _x x9 bb\nlast");
    assertEquals(List.of("_x"), index.complete("_", 10));
    assertEquals(List.of("bb"), index.complete("b", 10));
    assertEquals(List.of("x9"), index.complete("x", 10));
    assertEquals(List.of("last"), index.complete("la", 10));
    assertEquals(4, index.size());
  }

  @Test
  void completionIgnoresCaseLeavesOutThePrefixAndStopsAtTheMax() {
    WordIndex index = index("Value valueOf values VALUE val");
    assertEquals(List.of("VALUE", "Value", "valueOf", "values"), index.complete("val", 10));
    assertEquals(List.of("val", "VALUE"), index.complete("VA", 2));
    assertEquals(List.of(), index.complete("q", 10));
  }

  @Test
  void aWordOverTheLengthLimitIsLeftOut() {
    WordIndex index = index("w" + "x".repeat(WordIndex.MAX_LENGTH) + " wy");
    assertEquals(List.of("wy"), index.complete("w", 10));
  }

  @Test
  void occurrencesAreWholeWordsWithinTheRange() {
    Marked m = new Marked("foo food foo_ foo\nxfoo foo");
    assertArrayEquals(
        new int[] {0, 3, 14, 17, 23, 26},
        Occurrences.words(m.document.text(), "foo", 0, 26, 10, TOKEN));
    assertArrayEquals(
        new int[] {14, 17}, Occurrences.words(m.document.text(), "foo", 1, 20, 10, TOKEN));
    assertArrayEquals(
        new int[] {0, 3}, Occurrences.words(m.document.text(), "foo", 0, 26, 1, TOKEN));
  }
}
