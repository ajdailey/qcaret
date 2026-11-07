package io.github.ajdailey.qcaret.app.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.core.buffer.ChunkVisitor;
import io.github.ajdailey.qcaret.core.buffer.LineView;
import io.github.ajdailey.qcaret.core.buffer.TextCursor;
import io.github.ajdailey.qcaret.core.buffer.TextSnapshot;
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
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/**
 * Work counts of the projection, which a slow runner cannot change: text reads through a counting
 * snapshot, which the projection reads until its first document change, and lines visited and
 * measured per change through {@link DisplayProjection#linesVisited} and {@link
 * DisplayProjection#linesMeasured}. Each count is compared between two document sizes.
 */
class ProjectionWorkTest {
  private static final int SMALL = 2_000;
  private static final int LARGE = 20_000;
  private static final int COLUMNS = 10;
  private static final int REGION_LINES = 10;

  /** Counts every read of a text. */
  private static final class CountingText implements TextSnapshot {
    private final TextSnapshot text;
    long reads;
    long lineViews;
    long chunks;
    long chunkChars;
    long cancelAtLineView = -1;
    CancellationToken token;

    CountingText(TextSnapshot text) {
      this.text = text;
    }

    void reset() {
      reads = 0;
      lineViews = 0;
      chunks = 0;
      chunkChars = 0;
    }

    @Override
    public int length() {
      return text.length();
    }

    @Override
    public char charAt(int index) {
      reads++;
      return text.charAt(index);
    }

    @Override
    public CharSequence subSequence(int start, int end) {
      reads++;
      return text.subSequence(start, end);
    }

    @Override
    public int lineCount() {
      return text.lineCount();
    }

    @Override
    public int lineAt(int offset) {
      reads++;
      return text.lineAt(offset);
    }

    @Override
    public int offsetOfLine(int line) {
      reads++;
      return text.offsetOfLine(line);
    }

    @Override
    public void readChunks(int start, int end, ChunkVisitor visitor) {
      chunks++;
      chunkChars += end - start;
      text.readChunks(start, end, visitor);
    }

    @Override
    public int lineLength(int line) {
      reads++;
      return text.lineLength(line);
    }

    @Override
    public String line(int line) {
      reads++;
      return text.line(line);
    }

    @Override
    public LineView lineView(int line, LineView view) {
      reads++;
      lineViews++;
      if (lineViews == cancelAtLineView) {
        token.cancel();
      }
      return text.lineView(line, view);
    }

    @Override
    public TextCursor cursor(int offset) {
      reads++;
      return text.cursor(offset);
    }
  }

  /** A document, its counted text, and a projection reading that text. */
  private static final class Fixture {
    final Document document;
    final CountingText text;
    final DisplayProjection projection;

    Fixture(String content, int columns) {
      document =
          Document.of(
              content,
              TextEncoding.of(StandardCharsets.UTF_8, false),
              LineEnding.LF,
              UndoLimits.DEFAULTS);
      text = new CountingText(document.text());
      projection =
          new DisplayProjection(
              document, DisplayProjection.layout(text, columns, new CancellationToken()));
      text.reset();
    }

    /** Offers a region on the first nine lines of every ten and collapses them all. */
    void collapseRegions() {
      List<int[]> regions = new ArrayList<int[]>();
      for (int line = 0; line + REGION_LINES < text.lineCount(); line += REGION_LINES) {
        regions.add(new int[] {line, line + REGION_LINES - 2});
      }
      projection.setFoldRegions(regions);
      projection.foldAll();
      text.reset();
    }
  }

  /** Lines of 24 chars; each wraps to three rows at {@value #COLUMNS} columns. */
  private static String lines(int count) {
    return "words on a mock line 00\n".repeat(count - 1) + "words on a mock line 00";
  }

  private static int offsetOfLine(Fixture fixture, int line) {
    return fixture.document.text().offsetOfLine(line);
  }

  @Test
  void aWrapLayoutReadsEachLineOnce() {
    for (int size : new int[] {SMALL, LARGE}) {
      Fixture fixture = new Fixture(lines(size), 0);
      DisplayProjection.layout(fixture.text, COLUMNS, new CancellationToken());
      assertEquals(size, fixture.text.lineViews, "lines of " + size);
    }
  }

  @Test
  void aLayoutWithoutWrapScansOnceAndReadsOnlyLongLines() {
    String longLine = "x".repeat(DisplayProjection.MAX_ROW_CHARS + 1);
    for (int size : new int[] {SMALL, LARGE}) {
      Fixture fixture = new Fixture(lines(size) + "\n" + longLine + "\n" + longLine, 0);
      DisplayProjection.layout(fixture.text, 0, new CancellationToken());
      assertEquals(2, fixture.text.lineViews, "lines of " + size);
      assertEquals(fixture.text.length(), fixture.text.chunkChars, "chars of " + size);
      assertEquals(1, fixture.text.chunks, "scans of " + size);
    }
  }

  @Test
  void aCanceledLayoutStopsWithinOneCheckInterval() {
    final Fixture fixture = new Fixture(lines(5 * DisplayProjection.CHECK_LINES), 0);
    final CancellationToken token = new CancellationToken();
    fixture.text.token = token;
    fixture.text.cancelAtLineView = 5;
    assertThrows(
        CancellationException.class,
        new Executable() {
          @Override
          public void execute() {
            DisplayProjection.layout(fixture.text, COLUMNS, token);
          }
        });
    assertEquals(DisplayProjection.CHECK_LINES, fixture.text.lineViews);
  }

  @Test
  void anEditVisitsThreeLinesAtEverySize() {
    for (int columns : new int[] {0, COLUMNS}) {
      for (int size : new int[] {SMALL, LARGE}) {
        Fixture fixture = new Fixture(lines(size), columns);
        fixture.document.apply(EditBatch.replace(offsetOfLine(fixture, size / 2) + 2, 0, "typed"));
        String label = columns + " columns, lines of " + size;
        assertEquals(3, fixture.projection.linesVisited(), label);
        assertEquals(columns == 0 ? 0 : 3, fixture.projection.linesMeasured(), label);
      }
    }
  }

  @Test
  void spreadEditsVisitThreeLinesEach() {
    Fixture fixture = new Fixture(lines(LARGE), COLUMNS);
    List<Replacement> replacements = new ArrayList<Replacement>();
    int edits = 10;
    for (int i = 1; i <= edits; i++) {
      replacements.add(new Replacement(offsetOfLine(fixture, i * 1000), 0, "x"));
    }
    fixture.document.apply(EditBatch.of(EditBatch.Kind.TYPING, replacements));
    assertEquals(3 * edits, fixture.projection.linesVisited());
    assertEquals(3 * edits, fixture.projection.linesMeasured());
  }

  @Test
  void aChangeOverTheSyncLimitMeasuresNoLineOnTheUiThread() {
    String content = lines(DisplayProjection.SYNC_CHARS / 24 + 100);
    Fixture fixture = new Fixture(content, COLUMNS);
    final AtomicBoolean hook = new AtomicBoolean();
    fixture.projection.setRewrapHook(
        new Runnable() {
          @Override
          public void run() {
            hook.set(true);
          }
        });
    fixture.document.apply(EditBatch.replace(0, content.length(), content + "\n"));
    assertEquals(0, fixture.projection.linesMeasured());
    assertTrue(hook.get());
  }

  /** Counts the reads of painting a viewport of 50 rows in the middle of the text. */
  private static long viewportReads(int size) {
    Fixture fixture = new Fixture(lines(size), COLUMNS);
    DisplayProjection projection = fixture.projection;
    int first = projection.rowCount() / 2;
    int row = projection.rowAt(projection.rowStart(first));
    for (int i = 0; i < 50; i++) {
      projection.rowText(row + i);
      projection.lineOfRow(row + i);
    }
    projection.toModel(projection.rowStart(row));
    return fixture.text.reads;
  }

  @Test
  void aViewportReadsAboutTheSameAtEverySize() {
    long small = viewportReads(SMALL);
    long large = viewportReads(LARGE);
    assertTrue(small > 0);
    assertTrue(large <= small + 32, "reads " + small + " then " + large);
  }

  @Test
  void aRepaintOfCachedRowsReadsNoText() {
    Fixture fixture = new Fixture(lines(SMALL), COLUMNS);
    for (int row = 100; row < 150; row++) {
      fixture.projection.rowText(row);
    }
    fixture.text.reset();
    for (int row = 100; row < 150; row++) {
      fixture.projection.rowText(row);
    }
    assertEquals(0, fixture.text.reads);
  }

  /** Counts the reads of expanding and collapsing one region among those collapsed. */
  private static long foldReads(int size) {
    Fixture fixture = new Fixture(lines(size), COLUMNS);
    fixture.collapseRegions();
    int line = size / 2 / REGION_LINES * REGION_LINES;
    assertTrue(fixture.projection.unfold(line));
    assertTrue(fixture.projection.foldAt(line));
    return fixture.text.reads;
  }

  @Test
  void aFoldMeasuresOnlyItsOwnSpan() {
    long small = foldReads(SMALL);
    long large = foldReads(LARGE);
    assertTrue(large <= small + 8, "reads " + small + " then " + large);
    assertTrue(large <= 40, "reads " + large);
  }

  @Test
  void foldAllMeasuresEachSpanOnce() {
    for (int size : new int[] {SMALL, LARGE}) {
      Fixture fixture = new Fixture(lines(size), COLUMNS);
      List<int[]> regions = new ArrayList<int[]>();
      for (int line = 0; line + REGION_LINES < size; line += REGION_LINES) {
        regions.add(new int[] {line, line + REGION_LINES - 2});
      }
      fixture.projection.setFoldRegions(regions);
      fixture.text.reset();
      fixture.projection.foldAll();
      assertEquals(2L * regions.size(), fixture.text.lineViews, "lines of " + size);
    }
  }

  /** Counts the reads of the text before the first edit among collapsed regions. */
  private static long editReads(int size) {
    Fixture fixture = new Fixture(lines(size), COLUMNS);
    fixture.collapseRegions();
    int line = size / 2 / REGION_LINES * REGION_LINES + REGION_LINES - 1;
    fixture.document.apply(EditBatch.replace(offsetOfLine(fixture, line) + 2, 0, "typed"));
    assertEquals(3, fixture.projection.linesVisited());
    return fixture.text.reads;
  }

  @Test
  void anEditAmongCollapsedRegionsReadsAboutTheSameAtEverySize() {
    long small = editReads(SMALL);
    long large = editReads(LARGE);
    assertTrue(large <= small + 8, "reads " + small + " then " + large);
    assertTrue(large <= 60, "reads " + large);
  }
}
