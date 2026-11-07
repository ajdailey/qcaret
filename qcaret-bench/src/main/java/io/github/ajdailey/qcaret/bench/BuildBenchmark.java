package io.github.ajdailey.qcaret.bench;

import io.github.ajdailey.qcaret.core.buffer.RopeStorage;
import io.github.ajdailey.qcaret.core.buffer.TextStorage;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/** W1: build from decoded text, against the UTF-8 decode of the same text. */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(value = 2, jvmArgsAppend = "-XX:MaxRAMPercentage=50")
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
public class BuildBenchmark {
  /** Input kind. */
  @Param({"ASCII", "MIXED", "CRLF", "MIXED_EOL"})
  public Corpus corpus;

  /** Input size. */
  @Param({"1", "10", "100"})
  public int sizeMiB;

  private String text;
  private byte[] utf8;

  /** Generates the input. */
  @Setup
  public void setUp() {
    text = corpus.generate(sizeMiB * Corpus.MIB, Workloads.SEED);
    utf8 = text.getBytes(StandardCharsets.UTF_8);
  }

  /**
   * Builds the rope.
   *
   * @return the storage
   */
  @Benchmark
  public TextStorage build() {
    return RopeStorage.of(text);
  }

  /**
   * Decodes the UTF-8 bytes of the input: the baseline W1 is judged against.
   *
   * @return the text
   */
  @Benchmark
  public String decode() {
    return new String(utf8, StandardCharsets.UTF_8);
  }
}
