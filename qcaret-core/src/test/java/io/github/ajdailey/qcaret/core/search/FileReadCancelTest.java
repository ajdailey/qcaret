package io.github.ajdailey.qcaret.core.search;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.io.BinaryDetector;
import io.github.ajdailey.qcaret.core.io.FileStamp;
import io.github.ajdailey.qcaret.core.text.EncodingDetector;
import io.github.ajdailey.qcaret.core.text.TextEncoding;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * A cancel inside a file read: each stage of {@link FileSearch#decode} stops within one block of
 * {@link FileSearch#BLOCK_BYTES}, counted through {@link FileSearch#setProbe}, for the walk and for
 * {@link FileSearch#searchFile}, with the reservation released. The block-wise read gives the same
 * text, encoding, skip, stamp and digest as a whole-file read, detect, decode and digest.
 */
class FileReadCancelTest {
  /** Least blocks in the large file. */
  private static final int BLOCKS = 20;

  /** The block of a stage whose start cancels. */
  private static final int CANCEL_AT = 3;

  private static final String LINE = "mock text \u00e9 \u20ac \ud83d\ude00 needle\n";

  @TempDir Path temp;

  private final List<Throwable> failures = new CopyOnWriteArrayList<Throwable>();
  private Workers workers;

  @BeforeEach
  void create() {
    workers =
        new Workers(
            2,
            new Thread.UncaughtExceptionHandler() {
              @Override
              public void uncaughtException(Thread thread, Throwable error) {
                failures.add(error);
              }
            });
  }

  @AfterEach
  void close() {
    FileSearch.setProbe(null);
    workers.close();
    assertTrue(failures.isEmpty(), failures.toString());
  }

  private static FileSearch.Options options(Charset fallback, MemoryBudget budget) {
    return new FileSearch.Options(
        new SearchQuery("needle", false, true, false).compile(),
        FileFilter.of("", "", "", false),
        fallback,
        64L << 20,
        Integer.MAX_VALUE,
        budget);
  }

  /** UTF-8 text past every block and buffer size; its multi-byte chars straddle block ends. */
  private static byte[] large() {
    int lines = BLOCKS * FileSearch.BLOCK_BYTES / LINE.getBytes(StandardCharsets.UTF_8).length + 1;
    return LINE.repeat(lines).getBytes(StandardCharsets.UTF_8);
  }

  private Path write(String name, byte[] bytes) throws IOException {
    Path file = temp.resolve(name);
    Files.write(file, bytes);
    return file;
  }

  /** Counts the blocks of one stage of one file and cancels at the start of one of them. */
  private static final class Canceling implements FileSearch.Probe {
    private final Path file;
    private final FileSearch.Stage stage;
    private final CancellationToken token;
    final AtomicInteger blocks = new AtomicInteger();

    Canceling(Path file, FileSearch.Stage stage, CancellationToken token) {
      this.file = file;
      this.stage = stage;
      this.token = token;
    }

    @Override
    public void block(Path path, FileSearch.Stage at) {
      if (path.equals(file) && at == stage && blocks.incrementAndGet() == CANCEL_AT) {
        token.cancel();
      }
    }

    int after() {
      return blocks.get() - CANCEL_AT;
    }
  }

  @ParameterizedTest
  @EnumSource(FileSearch.Stage.class)
  void aCancelInEachStageStopsWithinOneBlock(FileSearch.Stage stage) throws IOException {
    final Path file = write("large.txt", large());
    final CancellationToken token = new CancellationToken();
    Canceling probe = new Canceling(file, stage, token);
    FileSearch.setProbe(probe);
    assertThrows(
        CancellationException.class,
        new Executable() {
          @Override
          public void execute() throws IOException {
            FileSearch.decode(file, options(StandardCharsets.UTF_8, null), token);
          }
        });
    assertTrue(probe.after() >= 0 && probe.after() <= 1, stage + " blocks after: " + probe.after());
  }

  @Test
  void aCancelInsideASingleFileReadThrowsAndReleasesTheReservation() throws IOException {
    final Path file = write("large.txt", large());
    final CancellationToken token = new CancellationToken();
    final MemoryBudget budget = new MemoryBudget(1L << 30, 100);
    Canceling probe = new Canceling(file, FileSearch.Stage.READ, token);
    FileSearch.setProbe(probe);
    assertThrows(
        CancellationException.class,
        new Executable() {
          @Override
          public void execute() {
            FileSearch.searchFile(file, options(StandardCharsets.UTF_8, budget), 100, token);
          }
        });
    assertTrue(probe.after() <= 1, "blocks after: " + probe.after());
    assertEquals(0, budget.reserved());
  }

  @Test
  void aCancelInsideAWalkReadCountsTheFileDoneWithNoSkip() throws IOException {
    Path file = write("large.txt", large());
    CancellationToken token = new CancellationToken();
    MemoryBudget budget = new MemoryBudget(1L << 30, 100);
    Canceling probe = new Canceling(file, FileSearch.Stage.DECODE, token);
    FileSearch.setProbe(probe);
    final List<String> results = new CopyOnWriteArrayList<String>();
    final AtomicInteger done = new AtomicInteger();
    FileSearch.Sink sink =
        new FileSearch.Sink() {
          @Override
          public int room() {
            return Integer.MAX_VALUE;
          }

          @Override
          public void found(FileSearch.FileHits hits) {
            results.add("found " + hits.path());
          }

          @Override
          public void skipped(Path skipped, FileSearch.Skip reason) {
            results.add(skipped + " " + reason);
          }

          @Override
          public void progress(int searched, int total) {
            done.accumulateAndGet(searched, Math::max);
          }
        };
    FileSearch.run(
        workers, temp, true, Set.of(), options(StandardCharsets.UTF_8, budget), sink, token);
    assertTrue(probe.after() <= 1, "blocks after: " + probe.after());
    assertTrue(results.isEmpty(), results.toString());
    assertEquals(1, done.get());
    assertEquals(0, budget.reserved());
  }

  /** The whole-file read, detect, decode and digest the block-wise read must match. */
  private static FileSearch.Decoded whole(Path file, Charset fallback, long max)
      throws IOException {
    FileStamp stamp = FileStamp.read(file);
    byte[] bytes = Files.readAllBytes(file);
    if (bytes.length > max) {
      return new FileSearch.Decoded(null, null, stamp, null, FileSearch.Skip.TOO_LARGE);
    }
    TextEncoding encoding = EncodingDetector.detect(bytes, bytes.length, fallback).encoding();
    if (BinaryDetector.isBinary(bytes, bytes.length, encoding.unitWidth() > 1)) {
      return new FileSearch.Decoded(null, encoding, stamp, null, FileSearch.Skip.BINARY);
    }
    int bom = encoding.bomBytes().length;
    String text =
        encoding
            .charset()
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
            .decode(ByteBuffer.wrap(bytes, bom, bytes.length - bom))
            .toString();
    return new FileSearch.Decoded(text, encoding, stamp, FileSearch.digest(bytes), null);
  }

  private void assertMatchesWholeRead(String name, byte[] bytes, Charset fallback, String detected)
      throws IOException {
    Path file = write(name, bytes);
    FileSearch.Options options = options(fallback, null);
    FileSearch.Decoded expected = whole(file, fallback, options.maxFileBytes());
    FileSearch.Decoded actual = FileSearch.decode(file, options, new CancellationToken());
    assertEquals(expected.skip(), actual.skip(), name);
    assertEquals(expected.encoding(), actual.encoding(), name);
    assertEquals(
        detected,
        actual.encoding().charset().name() + (actual.encoding().bom() ? " BOM" : ""),
        name);
    assertEquals(expected.stamp(), actual.stamp(), name);
    assertArrayEquals(expected.digest(), actual.digest(), name);
    assertEquals(expected.text(), actual.text(), name);
  }

  private static byte[] concat(byte[]... parts) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (byte[] part : parts) {
      out.writeBytes(part);
    }
    return out.toByteArray();
  }

  private static byte[] bytes(int... values) {
    byte[] bytes = new byte[values.length];
    for (int i = 0; i < values.length; i++) {
      bytes[i] = (byte) values[i];
    }
    return bytes;
  }

  /**
   * ASCII up to one byte before a block end, then {@code tail}, so tail straddles the block end.
   */
  private static byte[] straddling(byte[] tail) {
    return concat("a".repeat(FileSearch.BLOCK_BYTES - 1).getBytes(StandardCharsets.US_ASCII), tail);
  }

  @Test
  void theBlockWiseReadMatchesTheWholeFileReadForEveryEncoding() throws IOException {
    Charset utf8 = StandardCharsets.UTF_8;
    Charset cp1252 = Charset.forName("windows-1252");
    String text = new String(large(), utf8);
    byte[] utf8Bom = bytes(0xef, 0xbb, 0xbf);
    assertMatchesWholeRead("empty", new byte[0], utf8, "UTF-8");
    assertMatchesWholeRead("small", "needle\n".getBytes(utf8), utf8, "UTF-8");
    assertMatchesWholeRead("utf8", large(), utf8, "UTF-8");
    assertMatchesWholeRead("utf8-split", straddling("\u20ac needle".getBytes(utf8)), utf8, "UTF-8");
    assertMatchesWholeRead("utf8-bom", concat(utf8Bom, large()), utf8, "UTF-8 BOM");
    assertMatchesWholeRead("bom-only", utf8Bom, utf8, "UTF-8 BOM");
    assertMatchesWholeRead(
        "utf8-bom-malformed",
        concat(utf8Bom, straddling(bytes(0xe2, 0x82, 'a', 0xff, 0xc3)), large(), bytes(0xe2)),
        utf8,
        "UTF-8 BOM");
    assertMatchesWholeRead(
        "utf16le-bom", concat(bytes(0xff, 0xfe), text.getBytes("UTF-16LE")), utf8, "UTF-16LE BOM");
    assertMatchesWholeRead(
        "utf16be-bom-odd",
        concat(bytes(0xfe, 0xff), text.getBytes("UTF-16BE"), bytes('x')),
        utf8,
        "UTF-16BE BOM");
    assertMatchesWholeRead("utf16le-pattern", text.getBytes("UTF-16LE"), utf8, "UTF-16LE");
    assertMatchesWholeRead(
        "utf32be-bom",
        concat(bytes(0, 0, 0xfe, 0xff), text.getBytes("UTF-32BE")),
        utf8,
        "UTF-32BE BOM");
    assertMatchesWholeRead(
        "declared",
        concat(
            "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?>\n".getBytes(utf8),
            straddling(bytes(0xe9, 0xc3))),
        utf8,
        "ISO-8859-1");
    assertMatchesWholeRead("invalid-utf8", concat(large(), bytes(0xc3, 'a')), utf8, "ISO-8859-1");
    assertMatchesWholeRead("fallback", concat(large(), bytes(0x80, 0x9f)), cp1252, "windows-1252");
    assertMatchesWholeRead(
        "fallback-unmappable", concat(large(), bytes(0x80, 0x81)), cp1252, "ISO-8859-1");
    assertMatchesWholeRead("binary", concat(bytes('a', 0), large()), utf8, "UTF-8");
    FileSearch.Decoded tooLarge =
        FileSearch.decode(
            write("too-large", large()),
            new FileSearch.Options(
                null, null, utf8, FileSearch.BLOCK_BYTES, Integer.MAX_VALUE, null),
            new CancellationToken());
    assertEquals(FileSearch.Skip.TOO_LARGE, tooLarge.skip());
    assertNotNull(tooLarge.stamp());
    assertNull(tooLarge.digest());
  }
}
