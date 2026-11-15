package io.github.ajdailey.qcaret.core.text;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/**
 * The counter's {@code String} search against its char loop, over random breaks read in random
 * pieces, so a CR LF split between pieces is covered.
 */
class LineEndingStatsTest {
  @Test
  void aStringCountsAsItsCharsReadOneAtATime() {
    SplittableRandom random = new SplittableRandom(20261114L);
    String alphabet = "ab\r\n\n\r";
    for (int round = 0; round < 500; round++) {
      StringBuilder text = new StringBuilder();
      int length = random.nextInt(40);
      for (int i = 0; i < length; i++) {
        text.append(alphabet.charAt(random.nextInt(alphabet.length())));
      }
      String s = text.toString();
      LineEndingStats.Counter strings = new LineEndingStats.Counter();
      LineEndingStats.Counter chars = new LineEndingStats.Counter();
      int at = 0;
      while (at < s.length()) {
        int to = Math.min(s.length(), at + 1 + random.nextInt(6));
        strings.add(s, at, to);
        chars.add(s.toCharArray(), at, to);
        at = to;
      }
      assertEquals(chars.stats(), strings.stats(), "pieces of " + s);
      assertEquals(chars.stats(), LineEndingStats.count(s, 0, s.length()), s);
      assertEquals(chars.stats(), LineEndingStats.count(text, 0, text.length()), s);
    }
  }
}
