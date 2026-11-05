package io.github.ajdailey.qcaret.app.editor;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.document.Document;
import io.github.ajdailey.qcaret.core.document.EditBatch;
import io.github.ajdailey.qcaret.core.document.Replacement;
import io.github.ajdailey.qcaret.core.document.UndoLimits;
import io.github.ajdailey.qcaret.core.text.LineEnding;
import io.github.ajdailey.qcaret.core.text.TextEncoding;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/**
 * The projection against a display text built line by line from {@link RowBreaks}, after random
 * edits that join and part CR LF pairs, with wrap on and off; and rewrap requests and results.
 */
class DisplayProjectionTest {
  private static final int MAX = 7;
  private static final String ALPHABET = "ab \t\r\n\u4e00";

  private static Document document(String text) {
    return Document.of(
        text, TextEncoding.of(StandardCharsets.UTF_8, false), LineEnding.LF, UndoLimits.DEFAULTS);
  }

  private static DisplayProjection projection(Document document, int columns) {
    return new DisplayProjection(
        document,
        DisplayProjection.layout(
            document.text(), columns, DisplayProjection.TAB_WIDTH, MAX, new CancellationToken()),
        MAX);
  }

  /** The display text: each line's rows from {@link RowBreaks}, joined by {@code \n}. */
  private static String expected(String model, int columns) {
    StringBuilder out = new StringBuilder();
    int at = 0;
    while (true) {
      int end = at;
      while (end < model.length() && model.charAt(end) != '\r' && model.charAt(end) != '\n') {
        end++;
      }
      String line = model.substring(at, end);
      int[] breaks = RowBreaks.compute(line, columns, MAX, DisplayProjection.TAB_WIDTH);
      int from = 0;
      for (int b : breaks) {
        out.append(line, from, b).append('\n');
        from = b;
      }
      out.append(line, from, line.length());
      if (end == model.length()) {
        return out.toString();
      }
      int next = end + 1;
      if (model.charAt(end) == '\r' && next < model.length() && model.charAt(next) == '\n') {
        next++;
      }
      out.append(model, end, next);
      at = next;
    }
  }

  private static void check(DisplayProjection p, String model, int columns) {
    String display = expected(model, columns);
    assertEquals(display.length(), p.length());
    assertEquals(display, p.textRange(0, p.length()));
    int rows = p.rowCount();
    for (int row = 0; row < rows; row++) {
      int start = p.rowStart(row);
      String text = p.rowText(row);
      assertEquals(display.substring(start, start + text.length()), text, "row " + row);
      assertEquals(row, p.rowAt(start), "rowAt " + start);
    }
    for (int d = 0; d <= display.length(); d++) {
      int m = p.toModel(d);
      int back = p.toDisplay(m);
      assertTrue(back >= d - 2 && back <= d + 1, "display " + d + " model " + m + " back " + back);
    }
    for (int m = 0; m <= model.length(); m++) {
      int d = p.toDisplay(m);
      boolean insideCrLf =
          m > 0 && m < model.length() && model.charAt(m - 1) == '\r' && model.charAt(m) == '\n';
      if (!insideCrLf) {
        assertEquals(m, p.toModel(d), "model " + m);
      }
    }
  }

  @Test
  void wrapsAtSpacesAndSplitsLongRuns() {
    assertArrayEquals(new int[] {4}, RowBreaks.compute("abc def", 5, 100, 4));
    assertArrayEquals(new int[] {5}, RowBreaks.compute("abcdefgh", 5, 100, 4));
    assertArrayEquals(new int[] {3, 6}, RowBreaks.compute("abcdefgh", 0, 3, 4));
    assertArrayEquals(new int[] {2}, RowBreaks.compute("一一一", 4, 100, 4));
    assertArrayEquals(new int[] {1}, RowBreaks.compute("a\t", 3, 100, 4));
    // A surrogate pair stays whole at a char-limit split.
    assertArrayEquals(new int[] {1}, RowBreaks.compute("a😀", 0, 2, 4));
    assertArrayEquals(new int[0], RowBreaks.compute("abc", 0, 3, 4));
  }

  @Test
  void randomEditsKeepTheProjectionExact() {
    SplittableRandom random = new SplittableRandom(20261104L);
    for (int round = 0; round < 60; round++) {
      int columns = round % 2 == 0 ? 0 : 5;
      Document document = document(randomText(random, random.nextInt(40)));
      DisplayProjection p = projection(document, columns);
      final StringBuilder mirror = new StringBuilder(p.textRange(0, p.length()));
      final DisplayProjection projection = p;
      final int[] rowsBefore = new int[1];
      p.setListener(
          new DisplayProjection.Listener() {
            @Override
            public void changing(DisplayProjection.DisplayChange change) {
              assertEquals(mirror.toString(), projection.textRange(0, projection.length()));
              rowsBefore[0] = projection.rowCount();
            }

            @Override
            public void changed(DisplayProjection.DisplayChange change) {
              String now = projection.textRange(0, projection.length());
              mirror.replace(
                  change.start(),
                  change.start() + change.replaceChars(),
                  now.substring(change.start(), change.start() + change.newChars()));
              assertEquals(now, mirror.toString());
              assertEquals(
                  projection.rowCount() - rowsBefore[0], change.newRows() - change.replaceRows());
            }
          });
      for (int step = 0; step < 25; step++) {
        document.apply(randomBatch(random, document.text().length()));
        check(p, document.text().toString(), columns);
      }
      p.dispose();
    }
  }

  /** Runs rewraps as the owner does until none is needed. */
  private static void rewrap(DisplayProjection p) {
    DisplayProjection.Rewrap request = p.rewrapRequest();
    while (request != null) {
      p.install(request, DisplayProjection.compute(request, new CancellationToken()));
      request = p.rewrapRequest();
    }
  }

  @Test
  void aRewrapAppliesOnlyAtItsRevision() {
    StringBuilder text = new StringBuilder();
    for (int i = 0; i < 200; i++) {
      text.append("word word word word\n");
    }
    Document document = document(text.toString());
    DisplayProjection p = projection(document, 0);
    p.setViewColumns(10);
    p.setWrap(true);
    DisplayProjection.Rewrap request = p.rewrapRequest();
    assertTrue(p.rewrapPending());
    assertNull(p.rewrapRequest());
    DisplayProjection.Layout rows = DisplayProjection.compute(request, new CancellationToken());
    document.apply(EditBatch.replace(0, 0, "x"));
    p.install(request, rows);
    assertFalse(p.rewrapPending());
    check(p, document.text().toString(), 0);
    rewrap(p);
    check(p, document.text().toString(), 10);
    p.setWrap(false);
    rewrap(p);
    check(p, document.text().toString(), 0);
    assertFalse(p.wrap());
  }

  @Test
  void aNewRequestSupersedesTheRunningOne() {
    Document document = document("aaa bbb ccc ddd\n");
    DisplayProjection p = projection(document, 0);
    p.setViewColumns(4);
    p.setWrap(true);
    DisplayProjection.Rewrap first = p.rewrapRequest();
    p.setViewColumns(8);
    DisplayProjection.Rewrap second = p.rewrapRequest();
    p.install(first, DisplayProjection.compute(first, new CancellationToken()));
    assertTrue(p.rewrapPending());
    p.install(second, DisplayProjection.compute(second, new CancellationToken()));
    check(p, document.text().toString(), 8);
    p.setWrap(false);
    DisplayProjection.Rewrap off = p.rewrapRequest();
    p.setWrap(true);
    assertNull(p.rewrapRequest());
    p.install(off, DisplayProjection.compute(off, new CancellationToken()));
    check(p, document.text().toString(), 8);
  }

  @Test
  void aLargeChangeWithWrapOnSplitsAtOnceAndAsksForARewrap() {
    Document document = document("a b c d e f g\n");
    DisplayProjection p = projection(document, 0);
    p.setViewColumns(5);
    p.setWrap(true);
    rewrap(p);
    final int[] hooks = new int[1];
    p.setRewrapHook(
        new Runnable() {
          @Override
          public void run() {
            hooks[0]++;
          }
        });
    StringBuilder big = new StringBuilder();
    while (big.length() <= DisplayProjection.SYNC_CHARS) {
      big.append("ab cd ef gh\n");
    }
    document.apply(EditBatch.replace(0, 0, big.toString()));
    assertEquals(1, hooks[0]);
    // Only the split at the row char limit until the rewrap.
    assertArrayEquals(new int[] {MAX}, p.breaksOf(0));
    rewrap(p);
    assertArrayEquals(new int[] {3, 6}, p.breaksOf(0));
  }

  @Test
  void longLinesAreFoundByOneScan() {
    Document document = document("abcdefgh\r\nab\rabcdefghij\nabcdefgh");
    assertEquals(
        List.of(0, 2, 3),
        DisplayProjection.longLines(document.text(), 0, document.text().lineCount() - 1, MAX));
  }

  /**
   * Edits on distant lines fire one change over the lines between: one change per edited line
   * measured about 5 times slower in the widget for 10,000 spread carets.
   */
  @Test
  void editsOnDistantLinesFireOneChange() {
    StringBuilder text = new StringBuilder();
    for (int i = 0; i < 100; i++) {
      text.append("line ").append(i).append('\n');
    }
    Document document = document(text.toString());
    final DisplayProjection p = projection(document, 0);
    final List<String> seen = new ArrayList<String>();
    p.setListener(
        new DisplayProjection.Listener() {
          @Override
          public void changing(DisplayProjection.DisplayChange change) {
            seen.add(p.lineAt(p.toModel(change.start())) + "+" + change.replaceRows());
          }

          @Override
          public void changed(DisplayProjection.DisplayChange change) {
            seen.add("changed");
          }
        });
    int line10 = document.text().offsetOfLine(10);
    int line90 = document.text().offsetOfLine(90);
    document.apply(
        EditBatch.of(
            EditBatch.Kind.TYPING,
            List.of(new Replacement(line10, 0, "x"), new Replacement(line90, 0, "x"))));
    assertEquals(List.of("9+83", "changed"), seen);
    check(p, document.text().toString(), 0);
  }

  @Test
  void aTabWidthChangeWithWrapOnNeedsARewrap() {
    Document document = document("\tab cd\tef gh");
    DisplayProjection p = projection(document, 6);
    p.setViewColumns(6);
    p.setWrap(true);
    assertNull(p.rewrapRequest());
    p.setTabWidth(2);
    assertEquals(2, p.tabWidth());
    DisplayProjection.Rewrap request = p.rewrapRequest();
    p.install(request, DisplayProjection.compute(request, new CancellationToken()));
    assertNull(p.rewrapRequest());
    assertFalse(p.rewrapPending());
  }

  private static String randomText(SplittableRandom random, int length) {
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < length; i++) {
      out.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
    }
    return out.toString();
  }

  private static EditBatch randomBatch(SplittableRandom random, int length) {
    List<Replacement> replacements = new ArrayList<Replacement>();
    int at = 0;
    int count = 1 + random.nextInt(3);
    for (int i = 0; i < count && at <= length; i++) {
      int offset = at + random.nextInt(Math.max(1, length - at + 1));
      if (offset > length) {
        break;
      }
      int remove = random.nextInt(Math.min(5, length - offset) + 1);
      replacements.add(new Replacement(offset, remove, randomText(random, random.nextInt(9))));
      at = offset + remove + 1;
    }
    return EditBatch.of(EditBatch.Kind.COMMAND, replacements);
  }
}
