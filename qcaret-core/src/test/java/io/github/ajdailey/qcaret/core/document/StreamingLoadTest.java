package io.github.ajdailey.qcaret.core.document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.io.BinaryDetector;
import io.github.ajdailey.qcaret.core.io.Digests;
import io.github.ajdailey.qcaret.core.text.DetectionReason;
import io.github.ajdailey.qcaret.core.text.EncodingDetection;
import io.github.ajdailey.qcaret.core.text.EncodingDetector;
import io.github.ajdailey.qcaret.core.text.LineEnding;
import io.github.ajdailey.qcaret.core.text.LineEndingStats;
import io.github.ajdailey.qcaret.core.text.TextEncoding;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

/** The streaming load against a whole-array load of the same bytes. */
class StreamingLoadTest {
  private static final int BLOCK = 1 << 20;
  private static final String SAMPLE = "café 中文 あア 한 Ж € abc\r\nline 😀 two\n\r";
  private static final LoadOptions DEFAULTS = LoadOptions.DEFAULTS;

  @TempDir Path temp;

  /** What a load finds, compared field by field. */
  private record Found(
      String text,
      TextEncoding encoding,
      DetectionReason reason,
      boolean binary,
      boolean roundTrips,
      String digest,
      LineEndingStats endings,
      int lines) {}

  private static Found found(LoadedFile loaded) {
    Document document = loaded.document();
    return new Found(
        document.text().toString(),
        loaded.detection().encoding(),
        loaded.detection().reason(),
        loaded.binary(),
        loaded.roundTrips(),
        loaded.baseline().digest(),
        document.lineEndings(),
        document.text().lineCount());
  }

  /** Loads from one array: detection, strict decode and re-encode over the whole file. */
  private static Found wholeArrayLoad(byte[] bytes, LoadOptions options)
      throws CharacterCodingException {
    EncodingDetection detection =
        options.forced() != null
            ? EncodingDetector.forced(options.forced(), bytes, bytes.length)
            : EncodingDetector.detect(bytes, bytes.length, options.fallback());
    TextEncoding encoding = detection.encoding();
    int bom = encoding.bomBytes().length;
    String text =
        encoding
            .charset()
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes, bom, bytes.length - bom))
            .toString();
    boolean roundTrips = false;
    try {
      ByteBuffer encoded =
          encoding
              .charset()
              .newEncoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .encode(CharBuffer.wrap(text));
      roundTrips = encoded.equals(ByteBuffer.wrap(bytes, bom, bytes.length - bom));
    } catch (CharacterCodingException e) {
      roundTrips = false;
    }
    Document document = Document.of(text, encoding, options.empty(), options.limits());
    return new Found(
        text,
        encoding,
        detection.reason(),
        BinaryDetector.isBinary(bytes, bytes.length, encoding.unitWidth() > 1),
        roundTrips,
        Digests.of(bytes, bytes.length),
        document.lineEndings(),
        document.text().lineCount());
  }

  private Path write(byte[] bytes) throws IOException {
    Path file = temp.resolve("load.bin");
    Files.write(file, bytes);
    return file;
  }

  private static LoadedFile load(Path file, LoadOptions options, int blockBytes, int blockChars)
      throws IOException {
    return DocumentFiles.load(
        file, options, LoadReservation.UNMETERED, new CancellationToken(), blockBytes, blockChars);
  }

  /** The chars of {@link #SAMPLE} a charset can encode. */
  private static String encodable(Charset charset) {
    CharsetEncoder encoder = charset.newEncoder();
    StringBuilder text = new StringBuilder();
    int i = 0;
    while (i < SAMPLE.length()) {
      int n = Character.charCount(SAMPLE.codePointAt(i));
      CharSequence one = SAMPLE.subSequence(i, i + n);
      if (encoder.canEncode(one)) {
        text.append(one);
      }
      i += n;
    }
    return text.toString();
  }

  private static List<TextEncoding> encodings() {
    List<TextEncoding> encodings = new ArrayList<TextEncoding>();
    for (String name : TextEncoding.MENU) {
      encodings.add(TextEncoding.forName(name));
    }
    encodings.add(TextEncoding.of(StandardCharsets.UTF_8, true));
    encodings.add(TextEncoding.of(StandardCharsets.UTF_16LE, true));
    encodings.add(TextEncoding.of(StandardCharsets.UTF_16BE, true));
    encodings.add(TextEncoding.of(Charset.forName("UTF-32LE"), true));
    encodings.add(TextEncoding.of(Charset.forName("UTF-32BE"), true));
    encodings.add(TextEncoding.forName("ISO-2022-JP"));
    encodings.add(TextEncoding.forName("ISO-2022-KR"));
    return encodings;
  }

  /** Forces a charset; ISO-2022 escapes in a short text count as control bytes. */
  private static LoadOptions forced(TextEncoding encoding) {
    return new LoadOptions(
        encoding.charset(), DEFAULTS.fallback(), DEFAULTS.empty(), DEFAULTS.limits(), true);
  }

  private static byte[] bytes(TextEncoding encoding, String text) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.writeBytes(encoding.bomBytes());
    out.writeBytes(text.getBytes(encoding.charset()));
    return out.toByteArray();
  }

  /** Blocks of 1 to 9 bytes put a boundary at every offset of every sequence and CR LF. */
  @Test
  void everyEncodingLoadsTheSameAcrossEveryBlockBoundary() throws Exception {
    for (TextEncoding encoding : encodings()) {
      String text = encodable(encoding.charset()).repeat(3);
      byte[] bytes = bytes(encoding, text);
      Path file = write(bytes);
      LoadOptions options = forced(encoding);
      Found expected = wholeArrayLoad(bytes, options);
      assertEquals(text, expected.text(), encoding.toString());
      for (int block = 1; block <= 9; block++) {
        assertEquals(expected, found(load(file, options, block, 8)), encoding + " " + block);
      }
    }
  }

  /** The real block size, each charset with a sequence and a CR LF across the first boundary. */
  @Test
  void everyEncodingLoadsTheSameAcrossTheFirstRealBlockBoundary() throws Exception {
    for (TextEncoding encoding : encodings()) {
      String sequence = encodable(encoding.charset());
      int unit = encoding.unitWidth();
      int bom = encoding.bomBytes().length;
      for (int shift = 0; shift < 4 * unit; shift += unit) {
        int chars = (BLOCK - bom) / unit - shift / unit - 8;
        String text = "a".repeat(chars) + sequence;
        byte[] bytes = bytes(encoding, text);
        Path file = write(bytes);
        assertTrue(bytes.length > BLOCK, encoding.toString());
        LoadOptions options = forced(encoding);
        assertEquals(
            wholeArrayLoad(bytes, options),
            found(DocumentFiles.load(file, options, new CancellationToken())),
            encoding + " " + shift);
      }
    }
  }

  @Test
  void aCrEndingABlockBeforeAnLfIsOneBreak() throws Exception {
    byte[] bytes = ("a".repeat(BLOCK - 1) + "\r\nb\rc").getBytes(StandardCharsets.US_ASCII);
    LoadedFile loaded = load(write(bytes), DEFAULTS, BLOCK, 1 << 16);
    assertEquals(new LineEndingStats(0, 1, 1, LineEnding.CRLF), loaded.document().lineEndings());
    assertEquals(3, loaded.document().text().lineCount());
  }

  /** Text over every block, leaf and char buffer, with a late sequence at every char position. */
  @Test
  void aFileOverEveryBlockKeepsItsText() throws Exception {
    StringBuilder text = new StringBuilder();
    Random random = new Random(7);
    while (text.length() < 3 * BLOCK) {
      text.append(SAMPLE, 0, 1 + random.nextInt(SAMPLE.length() - 1));
    }
    byte[] bytes = text.toString().getBytes(StandardCharsets.UTF_8);
    LoadedFile loaded = DocumentFiles.load(write(bytes), DEFAULTS, new CancellationToken());
    assertEquals(wholeArrayLoad(bytes, DEFAULTS), found(loaded));
  }

  private static List<byte[]> corpus() {
    List<byte[]> corpus = new ArrayList<byte[]>();
    byte[] tail = "x".repeat(BLOCK + 10).getBytes(StandardCharsets.US_ASCII);
    corpus.add(new byte[0]);
    corpus.add("no line break".getBytes(StandardCharsets.UTF_8));
    corpus.add("\r".getBytes(StandardCharsets.UTF_8));
    corpus.add("a\rb\nc\r\nd\n\r".getBytes(StandardCharsets.UTF_8));
    corpus.add(SAMPLE.getBytes(StandardCharsets.UTF_8));
    corpus.add(new byte[] {(byte) 0xef, (byte) 0xbb, (byte) 0xbf, 'a'});
    corpus.add(new byte[] {(byte) 0xff, (byte) 0xfe, 'a', 0, '\r', 0, '\n', 0});
    corpus.add(new byte[] {(byte) 0xfe, (byte) 0xff, 0, 'a'});
    corpus.add(new byte[] {(byte) 0xff, (byte) 0xfe, 0, 0, 'a', 0, 0, 0});
    corpus.add("mock text\r\n".repeat(50).getBytes(StandardCharsets.UTF_16LE));
    corpus.add("mock text\r\n".repeat(50).getBytes(StandardCharsets.UTF_16BE));
    corpus.add("price €5\n".getBytes(Charset.forName("windows-1252")));
    corpus.add(new byte[] {'a', (byte) 0x81, (byte) 0xe9, '\n'});
    corpus.add(
        "<?xml version=\"1.0\" encoding=\"ISO-8859-2\"?>\nř"
            .getBytes(Charset.forName("ISO-8859-2")));
    corpus.add("<?xml version=\"1.0\" encoding=\"Shift_JIS\"?>\nあ".getBytes(sjis()));
    corpus.add(concat("<?xml encoding=\"US-ASCII\"?>\n".getBytes(), "é".getBytes()));
    corpus.add(concat("# -*- coding: latin-1 -*-\n".getBytes(), new byte[] {(byte) 0xe9}));
    corpus.add(concat(tail, new byte[] {(byte) 0xe9, 'z'}));
    corpus.add(concat(tail, new byte[] {(byte) 0x81, (byte) 0xe9}));
    corpus.add(concat(tail, new byte[] {(byte) 0xc3}));
    Random random = new Random(11);
    for (int i = 0; i < 40; i++) {
      byte[] noise = new byte[random.nextInt(300)];
      random.nextBytes(noise);
      for (int k = 0; k < noise.length; k++) {
        noise[k] = (byte) (noise[k] == 0 ? 'a' : noise[k]);
      }
      corpus.add(noise);
    }
    return corpus;
  }

  private static Charset sjis() {
    return Charset.forName("Shift_JIS");
  }

  private static byte[] concat(byte[] a, byte[] b) {
    byte[] out = Arrays.copyOf(a, a.length + b.length);
    System.arraycopy(b, 0, out, a.length, b.length);
    return out;
  }

  /** The detection chain, including a candidate that fails past the first block. */
  @Test
  void theCorpusLoadsAsAWholeArrayDoes() throws Exception {
    List<LoadOptions> forms =
        List.of(
            DEFAULTS.asBinary(),
            new LoadOptions(
                null, Charset.forName("windows-1252"), DEFAULTS.empty(), DEFAULTS.limits(), true),
            new LoadOptions(
                null, StandardCharsets.UTF_16LE, DEFAULTS.empty(), DEFAULTS.limits(), true));
    for (byte[] bytes : corpus()) {
      Path file = write(bytes);
      for (LoadOptions options : forms) {
        Found expected = wholeArrayLoad(bytes, options);
        assertEquals(expected, found(DocumentFiles.load(file, options, new CancellationToken())));
        assertEquals(expected, found(load(file, options, 3, 8)));
      }
    }
  }

  /** A NUL is binary unless a wide fallback wins, which only a pass can tell. */
  @Test
  void aBinaryHeadDependsOnTheCandidateThatWins() throws Exception {
    final Path file = write(new byte[] {(byte) 0xe9, 0, 0, 'a', 'b', 'c'});
    assertThrows(
        BinaryFileException.class,
        new Executable() {
          @Override
          public void execute() throws Throwable {
            DocumentFiles.load(file, DEFAULTS, new CancellationToken());
          }
        });
    LoadOptions wide =
        new LoadOptions(
            null, StandardCharsets.UTF_16LE, DEFAULTS.empty(), DEFAULTS.limits(), false);
    LoadedFile loaded = DocumentFiles.load(file, wide, new CancellationToken());
    assertEquals(StandardCharsets.UTF_16LE, loaded.detection().encoding().charset());
    assertFalse(loaded.binary());
  }

  /**
   * An auto-detecting decoder sees the file whole; split, it fails where the whole file decodes.
   */
  @Test
  void anAutoDetectingCharsetReadsTheFileAsOneBlock() throws Exception {
    Charset auto = Charset.forName("x-JISAutoDetect");
    byte[] bytes = SAMPLE.replaceAll("[^\\x00-\\x7f]", "x").getBytes(StandardCharsets.US_ASCII);
    LoadedFile loaded = load(write(bytes), DEFAULTS.withForced(auto), 1, 8);
    assertEquals(bytes.length, loaded.document().text().length());
    assertFalse(loaded.roundTrips());
  }

  /** A decoder that cancels the token as it starts its second call, in mid-file. */
  private static Charset canceling(final CancellationToken token) {
    return new Charset("x-mock-canceling", null) {
      @Override
      public boolean contains(Charset other) {
        return false;
      }

      @Override
      public CharsetDecoder newDecoder() {
        return new CharsetDecoder(this, 1, 1) {
          private int calls;

          @Override
          protected CoderResult decodeLoop(ByteBuffer in, CharBuffer out) {
            if (++calls == 2) {
              token.cancel();
            }
            while (in.hasRemaining() && out.hasRemaining()) {
              out.put((char) (in.get() & 0xff));
            }
            return in.hasRemaining() ? CoderResult.OVERFLOW : CoderResult.UNDERFLOW;
          }
        };
      }

      @Override
      public CharsetEncoder newEncoder() {
        return StandardCharsets.ISO_8859_1.newEncoder();
      }
    };
  }

  @Test
  void aCancelMidLoadFreesTheReservation() throws Exception {
    final Path file = write("a".repeat(3 * BLOCK).getBytes(StandardCharsets.US_ASCII));
    MemoryBudget budget = new MemoryBudget(1L << 30, 60);
    LoadCost cost = DocumentFiles.estimate(file, UndoLimits.DEFAULTS);
    final CancellationToken token = new CancellationToken();
    final LoadOptions options = DEFAULTS.withForced(canceling(token));
    try (final LoadReservation reservation = LoadReservation.reserve(budget, cost)) {
      assertNotNull(reservation);
      assertEquals(cost.reserveBytes(), budget.reserved());
      assertThrows(
          CancellationException.class,
          new Executable() {
            @Override
            public void execute() throws Throwable {
              DocumentFiles.load(file, options, reservation, token);
            }
          });
    }
    assertEquals(0, budget.reserved());
  }

  /**
   * Each leaf holds a char over U+00FF after an ASCII sample: 2 bytes per char, not the 1
   * estimated.
   */
  private static byte[] lateWideText(int bytes) {
    StringBuilder text = new StringBuilder("a".repeat(70_000));
    while (text.length() < bytes) {
      text.append("中").append("b".repeat(1000));
    }
    return text.toString().getBytes(StandardCharsets.UTF_8);
  }

  @Test
  void textCostingMoreThanTheSampleRaisesTheReservation() throws Exception {
    Path file = write(lateWideText(4 * BLOCK));
    MemoryBudget budget = new MemoryBudget(1L << 30, 60);
    LoadCost cost = DocumentFiles.estimate(file, UndoLimits.DEFAULTS);
    assertEquals(cost.fileBytes(), cost.textBytes());
    try (LoadReservation reservation = LoadReservation.reserve(budget, cost)) {
      LoadedFile loaded = DocumentFiles.load(file, DEFAULTS, reservation, new CancellationToken());
      assertTrue(budget.reserved() > cost.reserveBytes());
      assertTrue(loaded.retainedBytes() > cost.retainedBytes());
      long kept = reservation.keep(loaded.retainedBytes());
      assertEquals(kept, budget.reserved());
      budget.release(kept);
    }
    assertEquals(0, budget.reserved());
  }

  @Test
  void aRaiseTheBudgetRefusesEndsTheLoad() throws Exception {
    final Path file = write(lateWideText(4 * BLOCK));
    LoadCost cost = DocumentFiles.estimate(file, UndoLimits.DEFAULTS);
    MemoryBudget budget = new MemoryBudget(cost.reserveBytes() + 100, 100);
    try (final LoadReservation reservation = LoadReservation.reserve(budget, cost)) {
      LoadTooLargeException e =
          assertThrows(
              LoadTooLargeException.class,
              new Executable() {
                @Override
                public void execute() throws Throwable {
                  DocumentFiles.load(file, DEFAULTS, reservation, new CancellationToken());
                }
              });
      assertEquals(cost.fileBytes(), e.cost().fileBytes());
      assertTrue(e.cost().textBytes() > cost.textBytes());
      assertTrue(e.cost().reserveBytes() > cost.reserveBytes());
    }
    assertEquals(0, budget.reserved());
  }

  @Test
  void theKeptReservationIsTheTextLoaded() throws Exception {
    Path file = write(("é中" + "a".repeat(5000)).getBytes(StandardCharsets.UTF_8));
    LoadedFile loaded = DocumentFiles.load(file, DEFAULTS, new CancellationToken());
    long text = 2 * 2048 + 2048 + 906;
    assertEquals(5002, loaded.document().text().length());
    assertEquals(2 * text, loaded.retainedBytes());
  }
}
