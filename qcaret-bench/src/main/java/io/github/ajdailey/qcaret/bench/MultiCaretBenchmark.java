package io.github.ajdailey.qcaret.bench;

import io.github.ajdailey.qcaret.app.editor.ColumnMetrics;
import io.github.ajdailey.qcaret.app.editor.DisplayProjection;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.document.Document;
import io.github.ajdailey.qcaret.core.document.EditBatch;
import io.github.ajdailey.qcaret.core.document.Replacement;
import io.github.ajdailey.qcaret.core.document.Selection;
import io.github.ajdailey.qcaret.core.document.SelectionModel;
import io.github.ajdailey.qcaret.core.document.SelectionSet;
import io.github.ajdailey.qcaret.core.document.UndoLimits;
import io.github.ajdailey.qcaret.core.text.LineEnding;
import io.github.ajdailey.qcaret.core.text.TextEncoding;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

/**
 * One keystroke at every caret of a 10 MiB document, as {@code EditorController} types it: one
 * {@link EditBatch.Kind#TYPING} batch through {@link Document#apply} with the view's {@link
 * SelectionModel}, the undo merge and selection mapping, and a {@link DisplayProjection} following
 * the document. Sampled, so the output carries p50 and p99 per keystroke. The coalescing clock
 * advances 100 ms per keystroke, so an undo step closes every 10 keystrokes as at 10 chars a
 * second. The document is generated again before each iteration.
 */
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(value = 2, jvmArgsAppend = "-XX:MaxRAMPercentage=50")
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
public class MultiCaretBenchmark {
  /** Document size. */
  static final int SIZE = 10 * Corpus.MIB;

  private static final long KEYSTROKE_NANOS = TimeUnit.MILLISECONDS.toNanos(100);
  private static final ColumnMetrics METRICS = new ColumnMetrics(8);
  private static final int NARROW = METRICS.columnsIn(480);

  /** A document with carets spread evenly over it. */
  @State(Scope.Benchmark)
  public static class Carets {
    /** Carets typed at. */
    @Param({"1000", "10000"})
    public int carets;

    /** True to wrap the lines to two rows each. */
    @Param({"false", "true"})
    public boolean wrap;

    /** Chars generated. */
    int size = SIZE;

    Document document;
    DisplayProjection projection;
    SelectionModel model;

    /** Generates the document and places the carets. */
    @Setup(Level.Iteration)
    public void open() {
      final long[] now = new long[1];
      document =
          Document.of(
              Corpus.ASCII.generate(size, Workloads.SEED),
              TextEncoding.of(StandardCharsets.UTF_8, false),
              LineEnding.LF,
              UndoLimits.DEFAULTS,
              new LongSupplier() {
                @Override
                public long getAsLong() {
                  now[0] += KEYSTROKE_NANOS;
                  return now[0];
                }
              });
      projection =
          new DisplayProjection(
              document,
              DisplayProjection.layout(
                  document.text(), wrap ? NARROW : 0, new CancellationToken()));
      model = document.newSelectionModel();
      List<Selection> list = new ArrayList<Selection>(carets);
      for (int i = 0; i < carets; i++) {
        list.add(Selection.caret((int) ((long) size * i / carets)));
      }
      model.set(SelectionSet.of(list, 0));
    }

    /** Stops following the document. */
    @TearDown(Level.Iteration)
    public void close() {
      model.dispose();
      projection.dispose();
    }
  }

  /**
   * Types one char at every caret.
   *
   * @param s the document
   * @return the row count
   */
  @Benchmark
  public int keystroke(Carets s) {
    SelectionSet set = s.model.selections();
    List<Replacement> replacements = new ArrayList<Replacement>(set.size());
    List<Selection> after = new ArrayList<Selection>(set.size());
    int shift = 0;
    for (Selection selection : set.selections()) {
      int removed = selection.end() - selection.start();
      replacements.add(new Replacement(selection.start(), removed, "x"));
      after.add(Selection.caret(selection.start() + shift + 1));
      shift += 1 - removed;
    }
    s.document.apply(
        EditBatch.of(EditBatch.Kind.TYPING, replacements),
        s.model,
        SelectionSet.of(after, set.primaryIndex()));
    return s.projection.rowCount();
  }
}
