package io.github.ajdailey.qcaret.bench;

import io.github.ajdailey.qcaret.app.editor.DisplayProjection;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.document.Document;
import io.github.ajdailey.qcaret.core.document.DocumentChange;
import io.github.ajdailey.qcaret.core.document.DocumentListener;
import io.github.ajdailey.qcaret.core.document.EditBatch;
import io.github.ajdailey.qcaret.core.document.UndoLimits;
import io.github.ajdailey.qcaret.core.syntax.GrammarReader;
import io.github.ajdailey.qcaret.core.syntax.GrammarSet;
import io.github.ajdailey.qcaret.core.syntax.SyntaxEngine;
import io.github.ajdailey.qcaret.core.text.LineEnding;
import io.github.ajdailey.qcaret.core.text.TextEncoding;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Incremental highlighting of the generated 1,000,000-line Java text of {@link
 * SymbolHarness.Shape#JAVA}: an edit at the middle line handed to {@link SyntaxEngine#edit}, then
 * {@link SyntaxEngine#run} until {@link SyntaxEngine#idle}, the worker's work from one keystroke to
 * fold regions. {@code typeChar} changes no line's end state; {@code openComment} turns every later
 * line into a comment and back. No view range is set. {@code installRegions} is the UI thread's
 * share of a new region list: the copy {@code SyntaxHighlighter} keeps and {@link
 * DisplayProjection#setFoldRegions}.
 */
@BenchmarkMode(Mode.AverageTime)
@Fork(value = 2, jvmArgsAppend = "-XX:MaxRAMPercentage=50")
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
public class HighlightBenchmark {
  /** A highlighted document, an engine at its end and a projection of it. */
  @State(Scope.Benchmark)
  public static class Highlighted implements DocumentListener {
    /** Lines generated. */
    int lines = SymbolHarness.LINES;

    Document document;
    SyntaxEngine engine;
    DisplayProjection projection;
    List<int[]> regions;
    int lineStart;
    final CancellationToken token = new CancellationToken();

    /** Generates the document and highlights it to the end. */
    @Setup
    public void open() {
      GrammarSet set =
          GrammarSet.of(
              GrammarSet.readBuiltIns(new GrammarReader()), Map.of(), new ArrayList<String>());
      document =
          Document.of(
              SymbolHarness.Shape.JAVA.generate(lines),
              TextEncoding.of(StandardCharsets.UTF_8, false),
              LineEnding.LF,
              UndoLimits.DEFAULTS);
      engine =
          new SyntaxEngine(
              set.syntax("java"), document.text(), SyntaxEngine.DEFAULT_MAX_LINE_CHARS);
      projection =
          new DisplayProjection(
              document, DisplayProjection.layout(document.text(), 0, new CancellationToken()));
      document.addListener(this);
      lineStart = document.text().offsetOfLine(lines / 2 + 2);
      runToIdle();
      if (regions == null || regions.isEmpty()) {
        throw new IllegalStateException("no fold regions");
      }
    }

    /** Stops following the document. */
    @TearDown
    public void close() {
      document.removeListener(this);
      projection.dispose();
    }

    @Override
    public void textChanged(DocumentChange change) {
      engine.edit(change.edits(), document.text());
    }

    @Override
    public void dirtyChanged(boolean dirty) {}

    void runToIdle() {
      while (!engine.idle()) {
        SyntaxEngine.Slice slice = engine.run(Long.MAX_VALUE, token);
        if (slice.regions() != null) {
          regions = slice.regions();
        }
      }
    }

    void insertAndRemove(int offset, String text) {
      document.apply(EditBatch.replace(offset, 0, text));
      runToIdle();
      document.apply(EditBatch.replace(offset, text.length(), ""));
      runToIdle();
    }
  }

  /**
   * Types a char inside a line and removes it: two edits.
   *
   * @param s the document
   * @return the frontier
   */
  @Benchmark
  @OperationsPerInvocation(2)
  @OutputTimeUnit(TimeUnit.MILLISECONDS)
  public int typeChar(Highlighted s) {
    s.insertAndRemove(s.lineStart + 6, "x");
    return s.engine.frontier();
  }

  /**
   * Opens a block comment at a line's start and removes it: two edits, each changing the state of
   * every later line.
   *
   * @param s the document
   * @return the frontier
   */
  @Benchmark
  @OperationsPerInvocation(2)
  @OutputTimeUnit(TimeUnit.MILLISECONDS)
  public int openComment(Highlighted s) {
    s.insertAndRemove(s.lineStart, "/*");
    return s.engine.frontier();
  }

  /**
   * Copies the document's fold regions and installs them in the projection, none collapsed.
   *
   * @param s the document
   * @return the regions installed
   */
  @Benchmark
  @OutputTimeUnit(TimeUnit.MILLISECONDS)
  public int installRegions(Highlighted s) {
    s.projection.setFoldRegions(List.copyOf(s.regions));
    return s.regions.size();
  }
}
