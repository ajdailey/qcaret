package io.github.ajdailey.qcaret.bench;

import io.github.ajdailey.qcaret.app.editor.DisplayProjection;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.document.Document;
import io.github.ajdailey.qcaret.core.document.EditBatch;
import io.github.ajdailey.qcaret.core.document.Replacement;
import io.github.ajdailey.qcaret.core.document.Selection;
import io.github.ajdailey.qcaret.core.document.SelectionModel;
import io.github.ajdailey.qcaret.core.document.SelectionSet;
import io.github.ajdailey.qcaret.core.document.UndoLimits;
import io.github.ajdailey.qcaret.core.edit.Edit;
import io.github.ajdailey.qcaret.core.edit.LineTransforms;
import io.github.ajdailey.qcaret.core.text.LineEnding;
import io.github.ajdailey.qcaret.core.text.TextEncoding;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
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
 * Undo and redo of one large step through {@link Document#undo} and {@link Document#redo}, with a
 * {@link SelectionModel} restored and a {@link DisplayProjection} following the document, as on the
 * UI thread. The text is {@value #LINES} lines of 80 chars.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(value = 2, jvmArgsAppend = "-XX:MaxRAMPercentage=50")
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
public class UndoBenchmark {
  /** Lines of the document. */
  static final int LINES = 200_000;

  /** The step undone. */
  public enum Step {
    /** {@value Workloads#W5_MATCHES} evenly spaced 4-char replacements by 6 chars, one command. */
    REPLACE_ALL,
    /** Sort Lines of the whole text, one command. */
    SORT
  }

  /** A document whose last step is {@link #step}. */
  @State(Scope.Benchmark)
  public static class Undoable {
    /** The step undone. */
    @Param({"REPLACE_ALL", "SORT"})
    public Step step;

    /** Lines generated. */
    int lines = LINES;

    Document document;
    DisplayProjection projection;
    SelectionModel model;

    /** Generates the document and applies the step. */
    @Setup
    public void open() {
      document =
          Document.of(
              Corpus.ASCII.generate(lines * 80 - 1, Workloads.SEED),
              TextEncoding.of(StandardCharsets.UTF_8, false),
              LineEnding.LF,
              UndoLimits.DEFAULTS);
      projection =
          new DisplayProjection(
              document, DisplayProjection.layout(document.text(), 0, new CancellationToken()));
      model = document.newSelectionModel();
      if (step == Step.SORT) {
        Edit edit =
            LineTransforms.sort(
                document.text(),
                SelectionSet.caret(0),
                LineTransforms.Order.ASCENDING,
                new CancellationToken());
        document.apply(edit.batch(), model, edit.after());
      } else {
        int length = document.text().length();
        int every = length / Workloads.W5_MATCHES;
        List<Replacement> list = new ArrayList<Replacement>(Workloads.W5_MATCHES);
        for (int i = 0; i < Workloads.W5_MATCHES; i++) {
          list.add(new Replacement(i * every, 4, "mockup"));
        }
        document.apply(
            EditBatch.of(EditBatch.Kind.COMMAND, list),
            model,
            SelectionSet.of(List.of(Selection.caret(0)), 0));
      }
      if (!document.canUndo()) {
        throw new IllegalStateException("the undo limits dropped the step");
      }
    }

    /** Stops following the document. */
    @TearDown
    public void close() {
      model.dispose();
      projection.dispose();
    }
  }

  /**
   * Undoes the step and redoes it: two operations.
   *
   * @param s the document
   * @return the row count
   */
  @Benchmark
  @OperationsPerInvocation(2)
  public int undoAndRedo(Undoable s) {
    if (!s.document.undo(s.model) || !s.document.redo(s.model)) {
      throw new IllegalStateException("nothing to undo or redo");
    }
    return s.projection.rowCount();
  }
}
