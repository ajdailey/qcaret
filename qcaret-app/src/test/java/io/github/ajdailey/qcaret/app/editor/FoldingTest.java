package io.github.ajdailey.qcaret.app.editor;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * The fold layer against a display text built line by line with the collapsed regions' lines left
 * out, through random folds, unfolds, edits and rewraps; every display change is replayed on a
 * mirror of the widget's text.
 */
class FoldingTest {
  private static final int MAX = 7;
  private static final String ALPHABET = "ab \t\r\n\n一";

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

  /** Splits a text into {line, delimiter} pairs. */
  private static List<String[]> lines(String model) {
    List<String[]> out = new ArrayList<String[]>();
    int at = 0;
    while (true) {
      int end = at;
      while (end < model.length() && model.charAt(end) != '\r' && model.charAt(end) != '\n') {
        end++;
      }
      int next = end;
      if (end < model.length()) {
        next++;
        if (model.charAt(end) == '\r' && next < model.length() && model.charAt(next) == '\n') {
          next++;
        }
      }
      out.add(new String[] {model.substring(at, end), model.substring(end, next)});
      if (end == model.length()) {
        return out;
      }
      at = next;
    }
  }

  /**
   * The display rows, each with its delimiter: a shown line's rows, the last followed by the
   * delimiter of the last line it hides.
   */
  private static List<String[]> expected(String model, int columns, boolean[] hidden) {
    List<String[]> lines = lines(model);
    List<String[]> rows = new ArrayList<String[]>();
    for (int i = 0; i < lines.size(); i++) {
      if (hidden[i]) {
        continue;
      }
      String line = lines.get(i)[0];
      int[] breaks = RowBreaks.compute(line, columns, MAX, DisplayProjection.TAB_WIDTH);
      int from = 0;
      for (int b : breaks) {
        rows.add(new String[] {line.substring(from, b), "\n"});
        from = b;
      }
      int last = i;
      while (last + 1 < lines.size() && hidden[last + 1]) {
        last++;
      }
      rows.add(new String[] {line.substring(from), lines.get(last)[1]});
    }
    return rows;
  }

  private static boolean[] hiddenLines(DisplayProjection p) {
    FoldLayer folds = p.folds();
    boolean[] hidden = new boolean[p.lineCount()];
    for (int i = 0; i < folds.regionCount(); i++) {
      for (int line = folds.regionStart(i) + 1; line <= folds.regionEnd(i); line++) {
        hidden[line] = true;
      }
    }
    return hidden;
  }

  private static void check(DisplayProjection p, String model, int columns) {
    boolean[] hidden = hiddenLines(p);
    List<String[]> rows = expected(model, columns, hidden);
    StringBuilder display = new StringBuilder();
    assertEquals(rows.size(), p.rowCount());
    for (int row = 0; row < rows.size(); row++) {
      assertEquals(display.length(), p.rowStart(row), "row start " + row);
      assertEquals(rows.get(row)[0], p.rowText(row), "row " + row);
      assertEquals(row, p.rowAt(display.length()), "rowAt " + display.length());
      assertFalse(hidden[p.lineOfRow(row)], "row " + row + " shows a hidden line");
      if (p.subRow(row) == 0) {
        assertEquals(row, p.rowOfLine(p.lineOfRow(row)));
      }
      display.append(rows.get(row)[0]).append(rows.get(row)[1]);
    }
    assertEquals(display.toString(), p.textRange(0, p.length()));
    for (int m = 0; m <= model.length(); m++) {
      int line = p.lineAt(m);
      int d = p.toDisplay(m);
      assertTrue(d >= 0 && d <= p.length(), "model " + m);
      boolean insideCrLf =
          m > 0 && m < model.length() && model.charAt(m - 1) == '\r' && model.charAt(m) == '\n';
      if (insideCrLf) {
        continue;
      }
      if (hidden[line]) {
        assertTrue(p.hidden(line));
        int shown = p.visibleOffset(m, false);
        assertFalse(hidden[p.lineAt(shown)]);
        assertEquals(p.toDisplay(shown), d, "hidden model " + m);
      } else {
        assertEquals(m, p.toModel(d), "model " + m);
      }
    }
  }

  /** Replays each display change on a copy of the display text, as the widget does. */
  private static StringBuilder mirror(final DisplayProjection p) {
    final StringBuilder mirror = new StringBuilder(p.textRange(0, p.length()));
    final int[] rowsBefore = new int[1];
    p.setListener(
        new DisplayProjection.Listener() {
          @Override
          public void changing(DisplayProjection.DisplayChange change) {
            assertEquals(mirror.toString(), p.textRange(0, p.length()));
            rowsBefore[0] = p.rowCount();
            assertEquals(
                p.rowAt(change.start() + change.replaceChars()) - p.rowAt(change.start()),
                change.replaceRows(),
                "replaced rows");
          }

          @Override
          public void changed(DisplayProjection.DisplayChange change) {
            String now = p.textRange(0, p.length());
            String inserted = now.substring(change.start(), change.start() + change.newChars());
            mirror.replace(change.start(), change.start() + change.replaceChars(), inserted);
            assertEquals(now, mirror.toString());
            assertEquals(
                p.rowAt(change.start() + change.newChars()) - p.rowAt(change.start()),
                change.newRows(),
                "new rows");
            assertEquals(p.rowCount() - rowsBefore[0], change.newRows() - change.replaceRows());
          }
        });
    return mirror;
  }

  @Test
  void randomFoldsAndEditsKeepTheProjectionExact() {
    for (int round = 0; round < ROUNDS; round++) {
      round(20261104L + round, round % 2 == 0 ? 0 : 5);
    }
  }

  private static final int ROUNDS = Integer.getInteger("foldRounds", 80);

  /** One document through random fold changes and edits; a failure names the seed. */
  private static void round(long seed, int columns) {
    SplittableRandom random = new SplittableRandom(seed);
    Document document = document(randomText(random, 20 + random.nextInt(60)));
    DisplayProjection p = projection(document, columns);
    mirror(p);
    StringBuilder log = new StringBuilder("seed " + seed + " text " + escape(document.text()));
    try {
      for (int step = 0; step < 40; step++) {
        int lines = p.lineCount();
        int line = random.nextInt(lines);
        switch (random.nextInt(8)) {
          case 0, 1 -> {
            List<int[]> regions = randomRegions(random, lines);
            log.append("\nregions");
            for (int[] r : regions) {
              log.append(' ').append(r[0]).append('-').append(r[1]);
            }
            p.setFoldRegions(regions);
          }
          case 2 -> log.append("\nfold ").append(line).append(' ').append(p.fold(line));
          case 3 -> log.append("\nfoldAt ").append(line).append(' ').append(p.foldAt(line));
          case 4 -> log.append("\nunfold ").append(line).append(' ').append(p.unfold(line));
          case 5 -> {
            int pick = random.nextInt(4);
            if (pick == 0) {
              log.append("\nfoldAll ").append(p.foldAll());
            } else if (pick == 1) {
              log.append("\nunfoldAll ").append(p.unfoldAll());
            } else {
              log.append("\nreveal ").append(line).append(' ').append(p.reveal(line));
            }
          }
          default -> {
            EditBatch batch = randomBatch(random, document.text().length());
            log.append("\nedit");
            for (Replacement r : batch.replacements()) {
              log.append(' ').append(r.offset()).append('-').append(r.removeLength());
              log.append(" \"").append(escape(r.text())).append('"');
            }
            document.apply(batch);
            log.append(" -> ").append(escape(document.text()));
          }
        }
        check(p, document.text().toString(), columns);
      }
    } catch (AssertionError | IndexOutOfBoundsException | IllegalArgumentException e) {
      throw new AssertionError(log.toString(), e);
    }
    p.dispose();
  }

  private static String escape(CharSequence text) {
    return text.toString().replace("\r", "\\r").replace("\n", "\\n").replace("\t", "\\t");
  }

  @Test
  void rewrapsKeepTheFolds() {
    StringBuilder text = new StringBuilder();
    for (int i = 0; i < 30; i++) {
      text.append(i % 3 == 0 ? "head word word\n" : "\tbody word word word\n");
    }
    Document document = document(text.toString());
    DisplayProjection p = projection(document, 0);
    StringBuilder mirror = mirror(p);
    p.setFoldRegions(List.of(new int[] {0, 2}, new int[] {3, 5}, new int[] {9, 11}));
    assertTrue(p.foldAll());
    assertEquals(3, p.collapsedCount());
    p.setViewColumns(6);
    p.setWrap(true);
    DisplayProjection.Rewrap request = p.rewrapRequest();
    p.install(request, DisplayProjection.compute(request, new CancellationToken()));
    check(p, document.text().toString(), 6);
    assertEquals(mirror.toString(), p.textRange(0, p.length()));
  }

  @Test
  void foldAllMeasuredOnAWorkerInstallsOnlyAtItsTextRowsAndFolds() {
    Document document = document("a\n b\n c\nd\n e\nf\n");
    DisplayProjection p = projection(document, 0);
    StringBuilder mirror = mirror(p);
    p.setFoldRegions(List.of(new int[] {0, 2}, new int[] {3, 4}));

    DisplayProjection.FoldAll request = p.foldAllRequest();
    DisplayProjection.computeFoldAll(request);
    assertEquals(DisplayProjection.FoldAllOutcome.APPLIED, p.installFoldAll(request));
    assertEquals("a\nd\nf\n", p.textRange(0, p.length()));
    assertEquals(mirror.toString(), p.textRange(0, p.length()));
    assertEquals(null, p.foldAllRequest());

    // An edit after the request makes it stale; a fold command drops it.
    assertTrue(p.unfoldAll());
    request = p.foldAllRequest();
    DisplayProjection.computeFoldAll(request);
    document.apply(EditBatch.replace(document.text().length(), 0, "g\n"));
    assertEquals(DisplayProjection.FoldAllOutcome.STALE, p.installFoldAll(request));
    request = p.foldAllRequest();
    DisplayProjection.computeFoldAll(request);
    assertTrue(p.fold(0));
    assertEquals(DisplayProjection.FoldAllOutcome.DROPPED, p.installFoldAll(request));
    assertEquals(1, p.collapsedCount());

    // A rewrap that installs new rows makes it stale.
    request = p.foldAllRequest();
    DisplayProjection.computeFoldAll(request);
    p.setViewColumns(1);
    p.setWrap(true);
    DisplayProjection.Rewrap rewrap = p.rewrapRequest();
    p.install(rewrap, DisplayProjection.compute(rewrap, new CancellationToken()));
    assertEquals(DisplayProjection.FoldAllOutcome.STALE, p.installFoldAll(request));
    request = p.foldAllRequest();
    DisplayProjection.computeFoldAll(request);
    assertEquals(DisplayProjection.FoldAllOutcome.APPLIED, p.installFoldAll(request));
    assertEquals(2, p.collapsedCount());
    check(p, document.text().toString(), 1);
    assertEquals(mirror.toString(), p.textRange(0, p.length()));
  }

  @Test
  void anEditInsideAFoldOrOnItsLineBreakExpandsIt() {
    Document document = document("a\n b\n c\nd\n");
    DisplayProjection p = projection(document, 0);
    mirror(p);
    p.setFoldRegions(List.of(new int[] {0, 2}));
    assertTrue(p.fold(1));
    assertEquals("a\nd\n", p.textRange(0, p.length()));
    // Typing at the end of the first line keeps the fold.
    document.apply(EditBatch.replace(1, 0, "x"));
    assertTrue(p.collapsed(0));
    assertEquals("ax\nd\n", p.textRange(0, p.length()));
    // An edit above moves it.
    document.apply(EditBatch.replace(0, 0, "top\n"));
    assertTrue(p.collapsed(1));
    // A line break at the first line's end expands it.
    int end = document.text().offsetOfLine(2) - 1;
    document.apply(EditBatch.replace(end, 0, "\n"));
    assertFalse(p.collapsed(1));
    assertEquals(0, p.collapsedCount());
    check(p, document.text().toString(), 0);
    p.setFoldRegions(List.of(new int[] {1, 4}));
    assertTrue(p.fold(1));
    int hiddenOffset = document.text().offsetOfLine(3) + 1;
    document.apply(EditBatch.replace(hiddenOffset, 1, ""));
    assertEquals(0, p.collapsedCount());
    check(p, document.text().toString(), 0);
  }

  @Test
  void collapsedRegionsFollowTheOfferedRegions() {
    Document document = document("a\n b\n c\nd\n e\n");
    DisplayProjection p = projection(document, 0);
    mirror(p);
    p.setFoldRegions(List.of(new int[] {0, 2}, new int[] {3, 4}));
    assertTrue(p.foldAll());
    assertEquals("a\nd\n", p.textRange(0, p.length()));
    p.setFoldRegions(List.of(new int[] {0, 1}));
    assertEquals(1, p.collapsedCount());
    assertEquals(1, p.foldEnd(0));
    assertEquals("a\n c\nd\n e\n", p.textRange(0, p.length()));
    assertTrue(p.unfold(0));
    assertFalse(p.unfold(0));
    assertFalse(p.unfoldAll());
  }

  @Test
  void hiddenOffsetsMoveToTheShownLines() {
    Document document = document("a\n b\n c\nd");
    DisplayProjection p = projection(document, 0);
    p.setFoldRegions(List.of(new int[] {0, 2}));
    p.fold(0);
    int hidden = document.text().offsetOfLine(1) + 1;
    assertEquals(1, p.visibleOffset(hidden, false));
    assertEquals(document.text().offsetOfLine(3), p.visibleOffset(hidden, true));
    assertTrue(p.reveal(2));
    assertFalse(p.hidden(2));
    assertFalse(p.reveal(2));
  }

  @Test
  void indentationRegionsEndOnTheLastNonBlankLine() {
    Document document = document("a\n  b\n\n  c\n\nd\n\te\n        f\n");
    List<int[]> regions = new IndentFoldSource(8).regions(document.text(), 0);
    assertEquals(2, regions.size());
    assertArrayEquals(new int[] {0, 3}, regions.get(0));
    assertArrayEquals(new int[] {5, 7}, regions.get(1));
  }

  private static List<int[]> randomRegions(SplittableRandom random, int lines) {
    List<int[]> out = new ArrayList<int[]>();
    if (lines < 2) {
      return out;
    }
    int count = random.nextInt(6);
    for (int i = 0; i < count; i++) {
      int a = random.nextInt(lines - 1);
      int b = a + 1 + random.nextInt(Math.min(6, lines - 1 - a));
      out.add(new int[] {a, b});
    }
    return out;
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
      replacements.add(new Replacement(offset, remove, randomText(random, random.nextInt(6))));
      at = offset + remove + 1;
    }
    return EditBatch.of(EditBatch.Kind.COMMAND, replacements);
  }
}
