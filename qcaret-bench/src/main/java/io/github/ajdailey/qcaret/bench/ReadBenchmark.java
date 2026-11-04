package io.github.ajdailey.qcaret.bench;

import io.github.ajdailey.qcaret.core.buffer.ChunkVisitor;
import io.github.ajdailey.qcaret.core.buffer.TextSnapshot;
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
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/** W6 line lookups on 10 MiB and W7 sequential reads of 100 MiB, both after W3. */
@BenchmarkMode(Mode.AverageTime)
@Fork(value = 2, jvmArgsAppend = "-XX:MaxRAMPercentage=50")
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
public class ReadBenchmark {
  private static final int LOOKUPS = 1_000_000;

  /** A 10 MiB snapshot after W3 and 1,000,000 random offsets and lines. */
  @State(Scope.Benchmark)
  public static class Lookups {
    /** Candidate. */
    @Param({"PIECE_TREE", "ROPE", "GAP_BUFFER"})
    public Candidate candidate;

    /** Input kind. */
    @Param({"ASCII", "MIXED"})
    public Corpus corpus;

    TextSnapshot text;
    int[] offsets = new int[LOOKUPS];
    int[] lines = new int[LOOKUPS];

    /** Builds the document, applies W3 and draws the lookups. */
    @Setup
    public void build() {
      text = Workloads.afterW3(candidate, corpus, 10).snapshot();
      SplittableRandom random = new SplittableRandom(Workloads.SEED);
      for (int i = 0; i < LOOKUPS; i++) {
        offsets[i] = random.nextInt(text.length() + 1);
        lines[i] = random.nextInt(text.lineCount());
      }
    }
  }

  /** A 100 MiB snapshot after W3. */
  @State(Scope.Benchmark)
  public static class Whole {
    /** Candidate. */
    @Param({"PIECE_TREE", "ROPE", "GAP_BUFFER"})
    public Candidate candidate;

    /** Input kind. */
    @Param({"ASCII", "MIXED"})
    public Corpus corpus;

    TextSnapshot text;

    /** Builds the document and applies W3. */
    @Setup
    public void build() {
      text = Workloads.afterW3(candidate, corpus, 100).snapshot();
    }
  }

  /**
   * W6 {@code lineAt}.
   *
   * @param s the lookups
   * @return a checksum
   */
  @Benchmark
  @OperationsPerInvocation(LOOKUPS)
  @OutputTimeUnit(TimeUnit.NANOSECONDS)
  public long lineAt(Lookups s) {
    long sum = 0;
    for (int i = 0; i < LOOKUPS; i++) {
      sum += s.text.lineAt(s.offsets[i]);
    }
    return sum;
  }

  /**
   * W6 {@code offsetOfLine}.
   *
   * @param s the lookups
   * @return a checksum
   */
  @Benchmark
  @OperationsPerInvocation(LOOKUPS)
  @OutputTimeUnit(TimeUnit.NANOSECONDS)
  public long offsetOfLine(Lookups s) {
    long sum = 0;
    for (int i = 0; i < LOOKUPS; i++) {
      sum += s.text.offsetOfLine(s.lines[i]);
    }
    return sum;
  }

  /**
   * W6 {@code line(i)}.
   *
   * @param s the lookups
   * @param bh consumes each line
   */
  @Benchmark
  @OperationsPerInvocation(LOOKUPS)
  @OutputTimeUnit(TimeUnit.NANOSECONDS)
  public void line(Lookups s, Blackhole bh) {
    for (int i = 0; i < LOOKUPS; i++) {
      bh.consume(s.text.line(s.lines[i]));
    }
  }

  /**
   * W7 through {@code charAt}.
   *
   * @param s the text
   * @return a checksum
   */
  @Benchmark
  @OutputTimeUnit(TimeUnit.MILLISECONDS)
  public long charAt(Whole s) {
    TextSnapshot text = s.text;
    int len = text.length();
    long sum = 0;
    for (int i = 0; i < len; i++) {
      sum += text.charAt(i);
    }
    return sum;
  }

  /**
   * W7 through chunk iteration.
   *
   * @param s the text
   * @return a checksum
   */
  @Benchmark
  @OutputTimeUnit(TimeUnit.MILLISECONDS)
  public long chunks(Whole s) {
    final long[] sum = new long[1];
    s.text.readChunks(
        0,
        s.text.length(),
        new ChunkVisitor() {
          @Override
          public void chunk(CharSequence chunk, int start, int end) {
            long acc = 0;
            for (int i = start; i < end; i++) {
              acc += chunk.charAt(i);
            }
            sum[0] += acc;
          }
        });
    return sum[0];
  }
}
