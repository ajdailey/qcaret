package io.github.ajdailey.qcaret.core.document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/**
 * Composed batches against the same batches applied one after another: equal text, sorted changes
 * with text between them, and each change's removed text taken from the text before.
 */
class ChangeComposerTest {
  private static final String ALPHABET = "ab\ncd";

  @Test
  void randomBatchesComposeToTheirSequentialResult() {
    for (long seed = 0; seed < 2000; seed++) {
      SplittableRandom random = new SplittableRandom(seed);
      String text = word(random, random.nextInt(20));
      List<List<Change>> batches = new ArrayList<List<Change>>();
      String now = text;
      for (int n = 1 + random.nextInt(8); n > 0; n--) {
        List<Change> batch = batch(random, now);
        batches.add(batch);
        now = apply(now, batch);
      }
      List<Change> composed = ChangeComposer.compose(batches);
      String where = "seed " + seed;
      assertEquals(now, apply(text, composed), where);
      int end = -1;
      for (Change c : composed) {
        assertTrue(c.offset() > end, where);
        assertEquals(text.substring(c.offset(), c.offset() + c.removed().length()), c.removed());
        end = c.offset() + c.removed().length();
      }
    }
  }

  @Test
  void separateEditsStaySeparateAndTouchingOnesMerge() {
    List<Change> first = List.of(new Change(1, "", "x"), new Change(4, "e", ""));
    List<Change> second = List.of(new Change(2, "b", "B"), new Change(7, "", "y"));
    // "abcdefgh" -> "axbcdfgh" -> "axBcdfgyh"
    assertEquals(
        List.of(new Change(1, "b", "xB"), new Change(4, "e", ""), new Change(7, "", "y")),
        ChangeComposer.compose(first, second));
  }

  @Test
  void aTypedRunUndoesThroughItsComposedInverse() {
    String text = "one\ntwo\nthree\n";
    List<List<Change>> batches = new ArrayList<List<Change>>();
    String now = text;
    for (int line = 0; line < 3; line++) {
      int at = now.indexOf('\n', line == 0 ? 0 : nth(now, line) + 1);
      List<Change> batch = List.of(new Change(at, "", " // ok"));
      batches.add(batch);
      now = apply(now, batch);
    }
    List<Change> composed = ChangeComposer.compose(batches);
    assertEquals(3, composed.size());
    assertEquals(text, apply(now, Change.inverse(composed)));
  }

  private static int nth(String text, int line) {
    int at = -1;
    for (int i = 0; i < line; i++) {
      at = text.indexOf('\n', at + 1);
    }
    return at;
  }

  private static List<Change> batch(SplittableRandom random, String text) {
    List<Change> batch = new ArrayList<Change>();
    int at = 0;
    for (int n = 1 + random.nextInt(3); n > 0 && at <= text.length(); n--) {
      int offset = at + random.nextInt(text.length() - at + 1);
      int length = random.nextInt(Math.min(3, text.length() - offset) + 1);
      String inserted = word(random, random.nextInt(3));
      batch.add(new Change(offset, text.substring(offset, offset + length), inserted));
      at = offset + length + 1;
    }
    return batch;
  }

  private static String word(SplittableRandom random, int length) {
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < length; i++) {
      out.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
    }
    return out.toString();
  }

  private static String apply(String text, List<Change> batch) {
    StringBuilder out = new StringBuilder(text);
    for (int i = batch.size() - 1; i >= 0; i--) {
      Change c = batch.get(i);
      out.replace(c.offset(), c.offset() + c.removed().length(), c.inserted());
    }
    return out.toString();
  }
}
