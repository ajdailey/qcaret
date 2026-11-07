package io.github.ajdailey.qcaret.bench;

import io.github.ajdailey.qcaret.core.buffer.RopeStorage;
import io.github.ajdailey.qcaret.core.buffer.TextStorage;
import java.util.concurrent.TimeUnit;
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
import org.openjdk.jmh.annotations.Warmup;

/** W3 random edits, W5 replace-all and W8 undo and redo, on 10 MiB; one run per iteration. */
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(value = 2, jvmArgsAppend = "-XX:MaxRAMPercentage=50")
@Warmup(iterations = 5)
@Measurement(iterations = 10)
public class EditBenchmark {
  /** A fresh 10 MiB document per iteration. */
  @State(Scope.Benchmark)
  public static class Fresh {
    /** Input kind. */
    @Param({"ASCII", "MIXED", "CRLF", "MIXED_EOL"})
    public Corpus corpus;

    String text;
    Workloads.Edit[] edits;
    TextStorage storage;

    /** Generates the input and the W3 edits. */
    @Setup(Level.Trial)
    public void generate() {
      text = corpus.generate(10 * Corpus.MIB, Workloads.SEED);
      edits = Workloads.w3(text.length());
    }

    /** Builds the document. */
    @Setup(Level.Iteration)
    public void build() {
      storage = RopeStorage.of(text);
    }
  }

  /** A 10 MiB document after W3, with the text each edit removed. */
  @State(Scope.Benchmark)
  public static class Edited {
    /** Input kind. */
    @Param({"ASCII", "MIXED", "CRLF", "MIXED_EOL"})
    public Corpus corpus;

    Workloads.Edit[] edits;
    String[] removed;
    TextStorage storage;

    /** Builds the document and applies W3. */
    @Setup(Level.Iteration)
    public void build() {
      String text = corpus.generate(10 * Corpus.MIB, Workloads.SEED);
      edits = Workloads.w3(text.length());
      removed = new String[edits.length];
      storage = RopeStorage.of(text);
      Workloads.apply(storage, edits, removed);
    }
  }

  /**
   * W3: 100,000 random inserts and deletes of 1-20 chars.
   *
   * @param s the document
   * @return the storage
   */
  @Benchmark
  public TextStorage randomEdits(Fresh s) {
    Workloads.apply(s.storage, s.edits, null);
    return s.storage;
  }

  /**
   * W5: 100,000 replacements as one batch.
   *
   * @param s the document
   * @return the storage
   */
  @Benchmark
  public TextStorage replaceAll(Fresh s) {
    Workloads.replaceAll(s.storage);
    return s.storage;
  }

  /**
   * W8: undo all of W3, then redo all.
   *
   * @param s the document after W3
   * @return the storage
   */
  @Benchmark
  public TextStorage undoRedo(Edited s) {
    Workloads.undoRedo(s.storage, s.edits, s.removed);
    return s.storage;
  }
}
