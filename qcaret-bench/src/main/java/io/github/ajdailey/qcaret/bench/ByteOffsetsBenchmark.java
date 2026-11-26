package io.github.ajdailey.qcaret.bench;

import io.github.ajdailey.qcaret.core.buffer.RopeStorage;
import io.github.ajdailey.qcaret.core.buffer.TextSnapshot;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.hex.ByteOffsets;
import io.github.ajdailey.qcaret.core.hex.EncodedHexSource;
import io.github.ajdailey.qcaret.core.hex.HexBlocks;
import io.github.ajdailey.qcaret.core.text.TextEncoding;
import java.io.IOException;
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

/**
 * The hex pane's char-to-byte index: {@code build} over the whole text, {@code update} after an
 * edit at the middle, {@code charOffset} of a random byte, and {@code readPage}, one {@link
 * HexBlocks} block read through {@link EncodedHexSource} at a random offset.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@Fork(value = 2, jvmArgsAppend = "-XX:MaxRAMPercentage=50")
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
public class ByteOffsetsBenchmark {
  private static final int LOOKUPS = 1024;

  /** Input kind. */
  @Param({"ASCII", "MIXED"})
  public Corpus corpus;

  /** Input size in Mi chars. */
  @Param({"16", "256"})
  public int sizeMiB;

  /** The document's charset. */
  @Param({"UTF-8", "GB18030"})
  public String charset;

  private TextSnapshot text;
  private TextEncoding encoding;
  private ByteOffsets offsets;
  private EncodedHexSource source;
  private final long[] positions = new long[LOOKUPS];
  private final byte[] page = new byte[HexBlocks.BLOCK_BYTES];

  /** Generates the text, builds the index once and draws the byte offsets. */
  @Setup
  public void setUp() {
    text = RopeStorage.of(corpus.generate(sizeMiB * Corpus.MIB, Workloads.SEED)).snapshot();
    encoding = TextEncoding.forName(charset);
    offsets = ByteOffsets.build(text, encoding, new CancellationToken());
    source = new EncodedHexSource(offsets);
    SplittableRandom random = new SplittableRandom(Workloads.SEED);
    for (int i = 0; i < LOOKUPS; i++) {
      positions[i] = random.nextLong(offsets.size() - page.length);
    }
  }

  /**
   * Builds the index.
   *
   * @return the offsets
   */
  @Benchmark
  @OutputTimeUnit(TimeUnit.MILLISECONDS)
  public ByteOffsets build() {
    return ByteOffsets.build(text, encoding, new CancellationToken());
  }

  /**
   * Rebuilds the index from the middle of the text, as after an edit there.
   *
   * @return the offsets
   */
  @Benchmark
  @OutputTimeUnit(TimeUnit.MILLISECONDS)
  public ByteOffsets update() {
    return offsets.update(text, text.length() / 2, new CancellationToken());
  }

  /**
   * Maps random bytes to their chars.
   *
   * @return the sum of the char offsets
   */
  @Benchmark
  @OperationsPerInvocation(LOOKUPS)
  @OutputTimeUnit(TimeUnit.MICROSECONDS)
  public long charOffset() {
    long sum = 0;
    for (long position : positions) {
      sum += offsets.charOffset(position);
    }
    return sum;
  }

  /**
   * Reads one cache block at each of 16 random offsets.
   *
   * @return the sum of the first bytes
   * @throws IOException never; the source reads memory
   */
  @Benchmark
  @OperationsPerInvocation(16)
  @OutputTimeUnit(TimeUnit.MICROSECONDS)
  public long readPage() throws IOException {
    long sum = 0;
    for (int i = 0; i < 16; i++) {
      source.read(positions[i * 61], page, page.length, null);
      sum += page[0];
    }
    return sum;
  }
}
