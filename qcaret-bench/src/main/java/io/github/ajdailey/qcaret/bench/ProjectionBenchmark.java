package io.github.ajdailey.qcaret.bench;

import io.github.ajdailey.qcaret.app.editor.ColumnMetrics;
import io.github.ajdailey.qcaret.app.editor.DisplayProjection;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.document.Document;
import io.github.ajdailey.qcaret.core.document.EditBatch;
import io.github.ajdailey.qcaret.core.document.UndoLimits;
import io.github.ajdailey.qcaret.core.text.LineEnding;
import io.github.ajdailey.qcaret.core.text.TextEncoding;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

/**
 * {@link DisplayProjection} on a real {@link Document} without a widget: rows built, a rewrap at a
 * width change, a fold and Fold All, offset mapping both ways, and one edit's update. Column widths
 * come from a fixed {@link ColumnMetrics}. An edit runs through {@link Document#apply}, so its time
 * includes the rope and the undo step.
 */
@BenchmarkMode(Mode.AverageTime)
@Fork(value = 2, jvmArgsAppend = "-XX:MaxRAMPercentage=50")
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
public class ProjectionBenchmark {
  /** Lines of the {@code LINES} shapes. */
  static final int LINE_COUNT = 1_000_000;

  /** Lines from one offered fold region's start to the next. */
  static final int REGION_LINES = 10;

  private static final ColumnMetrics METRICS = new ColumnMetrics(8);
  private static final int NARROW = METRICS.columnsIn(480);
  private static final int WIDE = METRICS.columnsIn(576);
  private static final int LOOKUPS = 10_000;
  private static final int LONG_LINE_SYNC = 1_000_000;

  /** The text and rows projected. */
  public enum Shape {
    /** 1,000,000 lines of 80 chars, one row each. */
    LINES(LINE_COUNT * 80 - 1, 0, false),
    /** The same lines wrapped to two rows each. */
    LINES_WRAP(LINE_COUNT * 80 - 1, NARROW, false),
    /** The same lines, one row each, with a collapsed region every {@value #REGION_LINES}. */
    LINES_FOLDS(LINE_COUNT * 80 - 1, 0, true),
    /** One line of 4 MiB in rows of {@link DisplayProjection#MAX_ROW_CHARS}. */
    LONGLINE(4 * Corpus.MIB, 0, false),
    /** One wrapped line, the longest an edit rewraps on the UI thread. */
    LONGLINE_WRAP(LONG_LINE_SYNC, NARROW, false);

    private final int chars;
    private final int columns;
    private final boolean folds;

    Shape(int chars, int columns, boolean folds) {
      this.chars = chars;
      this.columns = columns;
      this.folds = folds;
    }

    private boolean oneLine() {
      return this == LONGLINE || this == LONGLINE_WRAP;
    }
  }

  /** A document, its projection, and a line away from any collapsed region. */
  static final class Projected {
    final Document document;
    final DisplayProjection projection;
    final int columns;
    final int editOffset;

    Projected(Shape shape) {
      columns = shape.columns;
      Corpus corpus = shape.oneLine() ? Corpus.LONGLINE : Corpus.ASCII;
      document =
          Document.of(
              corpus.generate(shape.chars, Workloads.SEED),
              TextEncoding.of(StandardCharsets.UTF_8, false),
              LineEnding.LF,
              UndoLimits.DEFAULTS);
      projection =
          new DisplayProjection(
              document,
              DisplayProjection.layout(document.text(), columns, new CancellationToken()));
      if (shape.folds) {
        offerRegions();
        projection.foldAll();
      }
      editOffset =
          shape.oneLine()
              ? shape.chars / 2
              : document.text().offsetOfLine(LINE_COUNT / 2 + REGION_LINES - 1);
    }

    /** Offers a region of the first nine lines of every {@value #REGION_LINES}. */
    void offerRegions() {
      List<int[]> regions = new ArrayList<int[]>(LINE_COUNT / REGION_LINES);
      for (int line = 0; line + REGION_LINES < LINE_COUNT; line += REGION_LINES) {
        regions.add(new int[] {line, line + REGION_LINES - 2});
      }
      projection.setFoldRegions(regions);
    }

    void close() {
      projection.dispose();
    }
  }

  /** The text and the wrap width of a {@link DisplayProjection#layout}. */
  @State(Scope.Benchmark)
  public static class Built {
    /** What is built. */
    @Param({"LINES", "LINES_WRAP", "LONGLINE", "LONGLINE_WRAP"})
    public Shape shape;

    Projected projected;

    /** Generates the document. */
    @Setup
    public void open() {
      projected = new Projected(shape);
    }

    /** Stops following the document. */
    @TearDown
    public void close() {
      projected.close();
    }
  }

  /** A wrapped projection whose width alternates. */
  @State(Scope.Benchmark)
  public static class Wrapped {
    /** What is rewrapped. */
    @Param({"LINES_WRAP", "LONGLINE_WRAP"})
    public Shape shape;

    Projected projected;
    int columns;

    /** Generates the document. */
    @Setup
    public void open() {
      projected = new Projected(shape);
      columns = NARROW;
    }

    /** Stops following the document. */
    @TearDown
    public void close() {
      projected.close();
    }
  }

  /** Offered regions and the region folded. */
  @State(Scope.Benchmark)
  public static class Folds {
    /** {@code LINES}: no other region collapsed. {@code LINES_FOLDS}: all of them. */
    @Param({"LINES", "LINES_FOLDS"})
    public Shape shape;

    Projected projected;
    int line;

    /** Generates the document and expands the region folded. */
    @Setup
    public void open() {
      projected = new Projected(shape);
      if (shape == Shape.LINES) {
        projected.offerRegions();
      }
      line = LINE_COUNT / 2;
      projected.projection.unfold(line);
    }

    /** Stops following the document. */
    @TearDown
    public void close() {
      projected.close();
    }
  }

  /** Offered regions, none collapsed between runs. */
  @State(Scope.Benchmark)
  public static class Expanded {
    Projected projected;

    /** Generates the document. */
    @Setup
    public void open() {
      projected = new Projected(Shape.LINES);
      projected.offerRegions();
    }

    /** Stops following the document. */
    @TearDown
    public void close() {
      projected.close();
    }
  }

  /** Random model and display offsets. */
  @State(Scope.Benchmark)
  public static class Lookups {
    /** What is mapped. */
    @Param({"LINES", "LINES_WRAP", "LINES_FOLDS", "LONGLINE_WRAP"})
    public Shape shape;

    Projected projected;
    int[] model = new int[LOOKUPS];
    int[] display = new int[LOOKUPS];

    /** Generates the document and draws the offsets. */
    @Setup
    public void open() {
      projected = new Projected(shape);
      SplittableRandom random = new SplittableRandom(Workloads.SEED);
      for (int i = 0; i < LOOKUPS; i++) {
        model[i] = random.nextInt(projected.projection.modelLength() + 1);
        display[i] = random.nextInt(projected.projection.length() + 1);
      }
    }

    /** Stops following the document. */
    @TearDown
    public void close() {
      projected.close();
    }
  }

  /** A projection edited on one line. */
  @State(Scope.Benchmark)
  public static class Typing {
    /** What is edited. */
    @Param({"LINES", "LINES_WRAP", "LINES_FOLDS", "LONGLINE", "LONGLINE_WRAP"})
    public Shape shape;

    Projected projected;

    /** Generates the document. */
    @Setup
    public void open() {
      projected = new Projected(shape);
    }

    /** Stops following the document. */
    @TearDown
    public void close() {
      projected.close();
    }
  }

  /** A projection given a line break. */
  @State(Scope.Benchmark)
  public static class Breaking {
    /** What is edited. */
    @Param({"LINES_WRAP", "LINES_FOLDS"})
    public Shape shape;

    Projected projected;

    /** Generates the document. */
    @Setup
    public void open() {
      projected = new Projected(shape);
    }

    /** Stops following the document. */
    @TearDown
    public void close() {
      projected.close();
    }
  }

  /**
   * Rows of the whole text.
   *
   * @param s the text
   * @return the rows
   */
  @Benchmark
  @OutputTimeUnit(TimeUnit.MILLISECONDS)
  public Object build(Built s) {
    return DisplayProjection.layout(
        s.projected.document.text(), s.projected.columns, new CancellationToken());
  }

  /**
   * A width change: the request, the worker's rows and their install.
   *
   * @param s the projection
   * @return the row count
   */
  @Benchmark
  @OutputTimeUnit(TimeUnit.MILLISECONDS)
  public int rewrap(Wrapped s) {
    DisplayProjection projection = s.projected.projection;
    s.columns = s.columns == NARROW ? WIDE : NARROW;
    projection.setViewColumns(s.columns);
    DisplayProjection.Rewrap request = projection.rewrapRequest();
    projection.install(request, DisplayProjection.compute(request, new CancellationToken()));
    return projection.rowCount();
  }

  /**
   * Collapses and expands one region.
   *
   * @param s the regions
   * @return the row count
   */
  @Benchmark
  @OperationsPerInvocation(2)
  @OutputTimeUnit(TimeUnit.MICROSECONDS)
  public int foldAndUnfold(Folds s) {
    DisplayProjection projection = s.projected.projection;
    projection.foldAt(s.line);
    projection.unfold(s.line);
    return projection.rowCount();
  }

  /**
   * Fold All as the view runs it, then Unfold All.
   *
   * @param s the regions
   * @return the row count
   */
  @Benchmark
  @OutputTimeUnit(TimeUnit.MILLISECONDS)
  public int foldAllAndUnfoldAll(Expanded s) {
    DisplayProjection projection = s.projected.projection;
    DisplayProjection.FoldAll request = projection.foldAllRequest();
    DisplayProjection.computeFoldAll(request);
    projection.installFoldAll(request);
    projection.unfoldAll();
    return projection.rowCount();
  }

  /**
   * Maps model offsets to display offsets.
   *
   * @param s the offsets
   * @return a checksum
   */
  @Benchmark
  @OperationsPerInvocation(LOOKUPS)
  @OutputTimeUnit(TimeUnit.NANOSECONDS)
  public long toDisplay(Lookups s) {
    DisplayProjection projection = s.projected.projection;
    long sum = 0;
    for (int i = 0; i < LOOKUPS; i++) {
      sum += projection.toDisplay(s.model[i]);
    }
    return sum;
  }

  /**
   * Maps display offsets to model offsets.
   *
   * @param s the offsets
   * @return a checksum
   */
  @Benchmark
  @OperationsPerInvocation(LOOKUPS)
  @OutputTimeUnit(TimeUnit.NANOSECONDS)
  public long toModel(Lookups s) {
    DisplayProjection projection = s.projected.projection;
    long sum = 0;
    for (int i = 0; i < LOOKUPS; i++) {
      sum += projection.toModel(s.display[i]);
    }
    return sum;
  }

  /**
   * Inserts a char and removes it: two edits.
   *
   * @param s the projection
   * @return the row count
   */
  @Benchmark
  @OperationsPerInvocation(2)
  @OutputTimeUnit(TimeUnit.MICROSECONDS)
  public int edit(Typing s) {
    return insertAndRemove(s.projected, "x");
  }

  /**
   * Inserts a line break and removes it: two edits.
   *
   * @param s the projection
   * @return the row count
   */
  @Benchmark
  @OperationsPerInvocation(2)
  @OutputTimeUnit(TimeUnit.MICROSECONDS)
  public int editLineBreak(Breaking s) {
    return insertAndRemove(s.projected, "\n");
  }

  private static int insertAndRemove(Projected projected, String text) {
    Document document = projected.document;
    document.apply(EditBatch.replace(projected.editOffset, 0, text));
    document.apply(EditBatch.replace(projected.editOffset, text.length(), ""));
    return projected.projection.rowCount();
  }
}
