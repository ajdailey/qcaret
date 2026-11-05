package io.github.ajdailey.qcaret.core.diff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * {@link LineDiff} against a dynamic-programming reference: seeded random edits of random line
 * lists, each script checked to rebuild both lists, to keep a longest common subsequence, and to
 * list removed lines before added ones; hunks checked for their context and starts.
 */
class LineDiffTest {
  private static final CancellationToken NONE = new CancellationToken();

  @Test
  void randomEditsGiveAShortestScript() {
    for (int seed = 0; seed < 400; seed++) {
      Random random = new Random(seed);
      List<String> before = lines(random, random.nextInt(40));
      List<String> after = edit(random, before);
      List<LineDiff.Line> script = LineDiff.diff(before, after, NONE);
      String name = "seed " + seed;
      assertEquals(before, side(script, true), name);
      assertEquals(after, side(script, false), name);
      assertEquals(lcs(before, after), count(script, LineDiff.Kind.SAME), name);
      assertRunsOrdered(script, name);
      checkHunks(script, before, after, 1 + random.nextInt(3), name);
    }
  }

  @Test
  void tooManyEditsReportOneChangeThatStillRebuildsBothLists() {
    Random random = new Random(7);
    List<String> before = new ArrayList<String>();
    List<String> after = new ArrayList<String>();
    for (int i = 0; i < LineDiff.MAX_EDITS + 200; i++) {
      before.add("a" + i);
      after.add("b" + i);
    }
    before.add(0, "same");
    after.add(0, "same");
    after.add(random.nextInt(after.size()), "inserted");
    List<LineDiff.Line> script = LineDiff.diff(before, after, NONE);
    assertEquals(before, side(script, true));
    assertEquals(after, side(script, false));
    assertEquals(LineDiff.Kind.SAME, script.get(0).kind());
    assertEquals(LineDiff.Kind.REMOVED, script.get(1).kind());
    assertEquals(LineDiff.Kind.ADDED, script.get(script.size() - 1).kind());
  }

  @Test
  void hunksKeepTheirContextAndMergeCloseChanges() {
    List<String> before = List.of("1", "2", "3", "4", "5", "6", "7", "8", "9", "10");
    List<String> after = List.of("1", "2", "x", "4", "5", "y", "7", "8", "9", "10");
    List<LineDiff.Hunk> close = LineDiff.hunks(LineDiff.diff(before, after, NONE), 1);
    assertEquals(1, close.size());
    assertEquals(1, close.get(0).before());
    assertEquals(8, close.get(0).lines().size());
    List<LineDiff.Hunk> apart = LineDiff.hunks(LineDiff.diff(before, after, NONE), 0);
    assertEquals(2, apart.size());
    assertEquals(5, apart.get(1).before());
    assertEquals(5, apart.get(1).after());
    assertTrue(LineDiff.hunks(LineDiff.diff(before, before, NONE), 3).isEmpty());
  }

  private static List<String> lines(Random random, int count) {
    List<String> lines = new ArrayList<String>();
    for (int i = 0; i < count; i++) {
      lines.add(String.valueOf((char) ('a' + random.nextInt(5))));
    }
    return lines;
  }

  /** Inserts, deletes and replaces lines at random. */
  private static List<String> edit(Random random, List<String> lines) {
    List<String> out = new ArrayList<String>(lines);
    int edits = random.nextInt(8);
    for (int i = 0; i < edits; i++) {
      int at = out.isEmpty() ? 0 : random.nextInt(out.size() + 1);
      switch (random.nextInt(3)) {
        case 0 -> out.add(at, String.valueOf((char) ('a' + random.nextInt(6))));
        case 1 -> {
          if (at < out.size()) {
            out.remove(at);
          }
        }
        default -> {
          if (at < out.size()) {
            out.set(at, "z" + random.nextInt(3));
          }
        }
      }
    }
    return out;
  }

  private static List<String> side(List<LineDiff.Line> script, boolean before) {
    List<String> out = new ArrayList<String>();
    for (LineDiff.Line line : script) {
      if (line.kind() == LineDiff.Kind.SAME
          || line.kind() == (before ? LineDiff.Kind.REMOVED : LineDiff.Kind.ADDED)) {
        out.add(line.text());
      }
    }
    return out;
  }

  private static int count(List<LineDiff.Line> script, LineDiff.Kind kind) {
    int n = 0;
    for (LineDiff.Line line : script) {
      n += line.kind() == kind ? 1 : 0;
    }
    return n;
  }

  /** The reference: the longest common subsequence's length by dynamic programming. */
  private static int lcs(List<String> a, List<String> b) {
    int[][] table = new int[a.size() + 1][b.size() + 1];
    for (int i = a.size() - 1; i >= 0; i--) {
      for (int j = b.size() - 1; j >= 0; j--) {
        table[i][j] =
            a.get(i).equals(b.get(j))
                ? table[i + 1][j + 1] + 1
                : Math.max(table[i + 1][j], table[i][j + 1]);
      }
    }
    return table[0][0];
  }

  private static void assertRunsOrdered(List<LineDiff.Line> script, String name) {
    for (int i = 1; i < script.size(); i++) {
      assertTrue(
          !(script.get(i - 1).kind() == LineDiff.Kind.ADDED
              && script.get(i).kind() == LineDiff.Kind.REMOVED),
          name + ": added line before a removed one at " + i);
    }
  }

  /** Every change lies in one hunk, with at most {@code context} unchanged lines at its ends. */
  private static void checkHunks(
      List<LineDiff.Line> script,
      List<String> before,
      List<String> after,
      int context,
      String name) {
    List<LineDiff.Hunk> hunks = LineDiff.hunks(script, context);
    int changes = 0;
    for (LineDiff.Hunk hunk : hunks) {
      List<LineDiff.Line> lines = hunk.lines();
      changes += lines.size() - count(lines, LineDiff.Kind.SAME);
      int lead = 0;
      while (lines.get(lead).kind() == LineDiff.Kind.SAME) {
        lead++;
      }
      int trail = 0;
      while (lines.get(lines.size() - 1 - trail).kind() == LineDiff.Kind.SAME) {
        trail++;
      }
      assertTrue(lead <= context && trail <= context, name + ": context");
      int b = hunk.before();
      int a = hunk.after();
      for (LineDiff.Line line : lines) {
        if (line.kind() != LineDiff.Kind.ADDED) {
          assertEquals(before.get(b++), line.text(), name + ": before line");
        }
        if (line.kind() != LineDiff.Kind.REMOVED) {
          assertEquals(after.get(a++), line.text(), name + ": after line");
        }
      }
    }
    assertEquals(script.size() - count(script, LineDiff.Kind.SAME), changes, name + ": changes");
  }
}
