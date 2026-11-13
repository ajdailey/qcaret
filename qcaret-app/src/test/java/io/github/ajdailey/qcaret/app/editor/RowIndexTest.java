package io.github.ajdailey.qcaret.app.editor;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/** {@link RowIndex#replaceAll} against {@link RowIndex#replace} applied range by range. */
class RowIndexTest {
  private static int[] breaks(SplittableRandom random) {
    int[] out = new int[random.nextInt(4)];
    int at = 0;
    for (int i = 0; i < out.length; i++) {
      at += 1 + random.nextInt(60);
      out[i] = at;
    }
    return out;
  }

  private static RowIndex.Builder lines(SplittableRandom random, int from, int count) {
    RowIndex.Builder builder = new RowIndex.Builder();
    for (int line = from; line < from + count; line++) {
      builder.add(line, breaks(random));
    }
    return builder;
  }

  private static void assertSame(RowIndex expected, RowIndex actual, int lineCount) {
    assertEquals(expected.entries(), actual.entries());
    assertEquals(expected.totalBreaks(), actual.totalBreaks());
    for (int line = 0; line < lineCount; line++) {
      assertArrayEquals(expected.breaksOf(line), actual.breaksOf(line), "line " + line);
      assertEquals(expected.breaksBefore(line), actual.breaksBefore(line), "line " + line);
    }
  }

  @Test
  void oneRunMatchesEachRangeReplacedFromTheLast() {
    SplittableRandom random = new SplittableRandom(42);
    for (int round = 0; round < 200; round++) {
      int lineCount = 50 + random.nextInt(200);
      RowIndex sequential = lines(random, 0, lineCount).build();
      RowIndex batched = sequential.copy();
      int n = 1 + random.nextInt(12);
      int[] from = new int[n];
      int[] oldEnd = new int[n];
      int[] newEnd = new int[n];
      RowIndex.Builder[] replacements = new RowIndex.Builder[n];
      int line = 0;
      int count = 0;
      while (count < n && line < lineCount) {
        from[count] = line + random.nextInt(4);
        if (from[count] >= lineCount) {
          break;
        }
        oldEnd[count] = Math.min(lineCount, from[count] + 1 + random.nextInt(3));
        newEnd[count] = from[count] + 1 + random.nextInt(4);
        replacements[count] = lines(random, from[count], newEnd[count] - from[count]);
        line = oldEnd[count];
        count++;
      }
      int delta = 0;
      for (int i = count - 1; i >= 0; i--) {
        sequential.replace(from[i], oldEnd[i], newEnd[i], replacements[i]);
        delta += newEnd[i] - oldEnd[i];
      }
      batched.replaceAll(
          Arrays.copyOf(from, count),
          Arrays.copyOf(oldEnd, count),
          Arrays.copyOf(newEnd, count),
          Arrays.copyOf(replacements, count));
      assertSame(sequential, batched, lineCount + delta);
    }
  }
}
