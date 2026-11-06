package io.github.ajdailey.qcaret.core.document;

import io.github.ajdailey.qcaret.core.buffer.TextContent;
import io.github.ajdailey.qcaret.core.buffer.TextCursor;
import io.github.ajdailey.qcaret.core.buffer.TextSnapshot;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.WorkerThread;
import io.github.ajdailey.qcaret.core.io.BinaryDetector;
import io.github.ajdailey.qcaret.core.io.ContentWriter;
import io.github.ajdailey.qcaret.core.io.Digests;
import io.github.ajdailey.qcaret.core.io.FileBaseline;
import io.github.ajdailey.qcaret.core.io.FileStamp;
import io.github.ajdailey.qcaret.core.io.SafeSave;
import io.github.ajdailey.qcaret.core.text.EncodingDetection;
import io.github.ajdailey.qcaret.core.text.EncodingDetector;
import io.github.ajdailey.qcaret.core.text.TextEncoding;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Loads a file into a {@link Document} and saves a {@link DocumentSnapshot} through {@link
 * SafeSave}. Both run on a worker, check their {@link CancellationToken} per block, and decode or
 * encode strictly: a malformed or unmappable sequence fails them instead of being replaced.
 */
public final class DocumentFiles {
  /** Largest file loaded, in bytes. */
  public static final long MAX_BYTES = Integer.MAX_VALUE - 8;

  private static final int BLOCK_BYTES = 1 << 20;
  private static final int BLOCK_CHARS = 1 << 16;

  /** Heap a load's buffers take beyond the text: four read blocks and the char buffers. */
  static final long BUFFER_BYTES = 5L * BLOCK_BYTES;

  /** Bytes {@link #estimate} reads from the start of a file. */
  static final int SAMPLE_BYTES = 64 * 1024;

  private static final Runnable NOTHING =
      new Runnable() {
        @Override
        public void run() {
          // No test between the passes.
        }
      };

  private DocumentFiles() {}

  /**
   * Estimates the heap a load of a file needs, from its size and its first {@value #SAMPLE_BYTES}
   * bytes. A char costs 1 byte when the sample is ASCII (a Latin-1 {@code String} leaf) and 2
   * otherwise; a UTF-16 or UTF-32 byte order mark divides the char count by 2 or 4. The peak is the
   * document's text plus the load's block buffers, at most {@value #BUFFER_BYTES} bytes; the
   * retained cost is the text plus undo headroom of the same size, at most {@code
   * limits.maxBytes()}. A file costing more past its sample raises its {@link LoadReservation}
   * during the load.
   *
   * @param file the file, validated by the caller
   * @param limits the undo bounds the document will have
   * @return the estimate
   * @throws IOException when the file cannot be read
   */
  @WorkerThread
  public static LoadCost estimate(Path file, UndoLimits limits) throws IOException {
    try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
      long size = channel.size();
      ByteBuffer sample = ByteBuffer.allocate((int) Math.min(SAMPLE_BYTES, size));
      while (sample.hasRemaining() && channel.read(sample) >= 0) {
        // Fill the sample.
      }
      return estimate(size, sample.array(), sample.position(), limits);
    }
  }

  static LoadCost estimate(long size, byte[] sample, int length, UndoLimits limits) {
    int unit = 1;
    if (length >= 4
        && ((sample[0] == 0
                && sample[1] == 0
                && (sample[2] & 0xff) == 0xfe
                && (sample[3] & 0xff) == 0xff)
            || ((sample[0] & 0xff) == 0xff
                && (sample[1] & 0xff) == 0xfe
                && sample[2] == 0
                && sample[3] == 0))) {
      unit = 4;
    } else if (length >= 2
        && (((sample[0] & 0xff) == 0xfe && (sample[1] & 0xff) == 0xff)
            || ((sample[0] & 0xff) == 0xff && (sample[1] & 0xff) == 0xfe))) {
      unit = 2;
    }
    boolean ascii = unit == 1;
    for (int i = 0; ascii && i < length; i++) {
      ascii = sample[i] >= 0;
    }
    long text = size / unit * (ascii ? 1 : 2);
    long retained = text + Math.min(limits.maxBytes(), text);
    return new LoadCost(size, text, text + Math.min(size, BUFFER_BYTES), retained);
  }

  /**
   * Loads a file outside the budget, as {@link #load(Path, LoadOptions, LoadReservation,
   * CancellationToken)} with {@link LoadReservation#UNMETERED}.
   *
   * @param file the file, validated by the caller
   * @param options the forced charset, fallback and undo bounds
   * @param token canceled by a close of the document or the window
   * @return the document and what the load found
   * @throws IOException as the full form does
   */
  @WorkerThread
  public static LoadedFile load(Path file, LoadOptions options, CancellationToken token)
      throws IOException {
    return load(file, options, LoadReservation.UNMETERED, token);
  }

  /**
   * Loads a file without holding its bytes. The first {@value EncodingDetector#SAMPLE_BYTES} bytes
   * give the encoding candidates ({@link EncodingDetector#candidates}, or the forced charset) and
   * the binary check; a binary file is rejected unless allowed. Then each candidate in turn takes
   * one {@link LoadPass} over the file, which ends at the first byte the candidate cannot decode,
   * so a file in its first candidate costs one read. One channel serves every pass and reads at
   * most the size it had on opening; a file replaced by a rename during the load stays the file
   * opened. A file written in place between passes yields the text of the pass that finished, with
   * that pass's digest.
   *
   * @param file the file, validated by the caller
   * @param options the forced charset, fallback and undo bounds
   * @param reservation charged as the text arrives
   * @param token canceled by a close of the document or the window
   * @return the document and what the load found
   * @throws BinaryFileException for a binary file when {@code options.allowBinary()} is false
   * @throws TextCodingException when the bytes do not decode in the chosen charset
   * @throws LoadTooLargeException when the text costs more than the budget can hold
   * @throws IOException when the file cannot be read or exceeds {@link #MAX_BYTES}
   * @throws java.util.concurrent.CancellationException when canceled
   */
  @WorkerThread
  public static LoadedFile load(
      Path file, LoadOptions options, LoadReservation reservation, CancellationToken token)
      throws IOException {
    return load(file, options, reservation, token, BLOCK_BYTES, BLOCK_CHARS);
  }

  static LoadedFile load(
      Path file,
      final LoadOptions options,
      LoadReservation reservation,
      CancellationToken token,
      int blockBytes,
      int blockChars)
      throws IOException {
    return read(
        file,
        options,
        reservation,
        token,
        blockBytes,
        blockChars,
        new Reading<DocumentBuilder, LoadedFile>() {
          @Override
          public DocumentBuilder sink() {
            return new DocumentBuilder();
          }

          @Override
          public LoadedFile finish(Finished read, DocumentBuilder text) {
            return new LoadedFile(
                text.build(read.detection().encoding(), options.empty(), options.limits()),
                read.baseline(),
                read.detection(),
                read.binary(),
                read.pass().roundTrips(),
                text.retainedBytes(options.limits()));
          }
        });
  }

  /**
   * Compares a file with an open document's text without holding the file's text. It reads the file
   * as {@link #load(Path, LoadOptions, LoadReservation, CancellationToken)} does, up to two passes:
   * the first finds the common prefix and the char count, the second, when the texts differ,
   * compares the rest aligned at the end and collects the replacement's text. {@code reservation}
   * is charged for the load's buffers and the changed span: the inserted text twice and the removed
   * text once, which {@link Document#apply} holds at its peak.
   *
   * @param file the file, validated by the caller
   * @param options the forced charset, fallback and undo bounds
   * @param current the document's text
   * @param reservation charged as the span grows; the caller holds it until the span is applied
   * @param token canceled by a close of the document or the window
   * @return the replacement and what the read found
   * @throws BinaryFileException for a binary file when {@code options.allowBinary()} is false
   * @throws TextCodingException when the bytes do not decode in the chosen charset
   * @throws LoadTooLargeException when the span costs more than the budget can hold
   * @throws IOException when the file cannot be read, exceeds {@link #MAX_BYTES} or differs between
   *     the passes
   * @throws java.util.concurrent.CancellationException when canceled
   */
  @WorkerThread
  public static ReloadedFile reload(
      Path file,
      LoadOptions options,
      TextContent current,
      LoadReservation reservation,
      CancellationToken token)
      throws IOException {
    return reload(file, options, current, reservation, token, BLOCK_BYTES, BLOCK_CHARS, NOTHING);
  }

  static ReloadedFile reload(
      Path file,
      final LoadOptions options,
      final TextContent current,
      final LoadReservation reservation,
      final CancellationToken token,
      final int blockBytes,
      final int blockChars,
      final Runnable betweenPasses)
      throws IOException {
    final long buffers = Math.min(Files.size(file), BUFFER_BYTES);
    return read(
        file,
        options,
        reservation,
        token,
        blockBytes,
        blockChars,
        new Reading<TextDifference.Prefix, ReloadedFile>() {
          @Override
          public TextDifference.Prefix sink() {
            return new TextDifference.Prefix(current, buffers);
          }

          @Override
          public ReloadedFile finish(Finished read, TextDifference.Prefix prefix)
              throws IOException {
            TextEncoding encoding = read.detection().encoding();
            Replacement replacement = null;
            if (!prefix.equalsCurrent()) {
              betweenPasses.run();
              TextDifference.Span span = new TextDifference.Span(current, prefix, buffers);
              LoadPass pass =
                  new LoadPass(
                      read.channel(),
                      read.size(),
                      encoding,
                      reservation,
                      token,
                      blockBytes,
                      blockChars,
                      span);
              try {
                pass.run();
              } catch (TextCodingException e) {
                throw new IOException("The file changed during the read", e);
              }
              if (!pass.digest().equals(read.baseline().digest())
                  || span.length() != prefix.length()) {
                throw new IOException("The file changed during the read");
              }
              reservation.charge(span.finishedBytes(), 0);
              replacement = span.replacement();
            }
            return new ReloadedFile(
                replacement, encoding, prefix.endings().dominant(options.empty()), read.baseline());
          }
        });
  }

  /** What one file read makes of its candidates' passes. */
  private interface Reading<S extends TextSink, R> {
    /** Returns the sink of the next candidate's pass. */
    S sink();

    /** Makes the result from the pass that finished and its sink. */
    R finish(Finished read, S sink) throws IOException;
  }

  /** The pass that finished, on the channel still open. */
  private record Finished(
      FileChannel channel,
      long size,
      LoadPass pass,
      EncodingDetection detection,
      boolean binary,
      FileBaseline baseline) {}

  private static <S extends TextSink, R> R read(
      Path file,
      LoadOptions options,
      LoadReservation reservation,
      CancellationToken token,
      int blockBytes,
      int blockChars,
      Reading<S, R> reading)
      throws IOException {
    long recorded = System.currentTimeMillis();
    FileStamp stamp = FileStamp.read(file);
    try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
      long size = channel.size();
      if (size > MAX_BYTES) {
        throw new IOException("File too large: " + size + " bytes");
      }
      if (stamp == null) {
        throw new NoSuchFileException(String.valueOf(file));
      }
      token.check();
      ByteBuffer head = ByteBuffer.allocate((int) Math.min(EncodingDetector.SAMPLE_BYTES, size));
      while (head.hasRemaining() && channel.read(head) >= 0) {
        // Fill the head.
      }
      int length = head.position();
      List<EncodingDetection> candidates =
          options.forced() != null
              ? List.of(EncodingDetector.forced(options.forced(), head.array(), length))
              : EncodingDetector.candidates(head.array(), length, options.fallback());
      if (!options.allowBinary() && everyBinary(candidates, head.array(), length)) {
        throw new BinaryFileException(String.valueOf(file.getFileName()));
      }
      for (int i = 0; ; i++) {
        EncodingDetection detection = candidates.get(i);
        TextEncoding encoding = detection.encoding();
        S sink = reading.sink();
        LoadPass pass =
            new LoadPass(channel, size, encoding, reservation, token, blockBytes, blockChars, sink);
        try {
          pass.run();
        } catch (TextCodingException e) {
          if (i == candidates.size() - 1) {
            throw e;
          }
          continue;
        }
        boolean binary = binary(head.array(), length, encoding);
        if (binary && !options.allowBinary()) {
          throw new BinaryFileException(String.valueOf(file.getFileName()));
        }
        FileBaseline baseline = new FileBaseline(stamp, pass.digest(), recorded);
        return reading.finish(new Finished(channel, size, pass, detection, binary, baseline), sink);
      }
    }
  }

  private static boolean binary(byte[] head, int length, TextEncoding encoding) {
    return BinaryDetector.isBinary(head, length, encoding.unitWidth() > 1);
  }

  /** Reports whether the file is binary whichever candidate wins, so no pass is needed. */
  private static boolean everyBinary(List<EncodingDetection> candidates, byte[] head, int length) {
    for (EncodingDetection candidate : candidates) {
      if (!binary(head, length, candidate.encoding())) {
        return false;
      }
    }
    return true;
  }

  /**
   * Saves a snapshot: checks that every char encodes, then writes the BOM and the text through
   * {@link SafeSave}. The line breaks are written as the text holds them.
   *
   * @param file the target as the user named it
   * @param snapshot the version to write and its encoding
   * @param baseline the stamp from the load or the last save, or null for a new file
   * @param token canceled by a close of the document or Esc; a cancel before the target is replaced
   *     leaves it unchanged
   * @return the saved file's stamp and the SHA-256 of the bytes written
   * @throws TextCodingException when a char cannot be encoded; no file is touched
   * @throws io.github.ajdailey.qcaret.core.io.ExternalModificationException when the target changed
   *     since {@code baseline}
   * @throws IOException when the write fails
   */
  @WorkerThread
  public static FileBaseline save(
      Path file, final DocumentSnapshot snapshot, FileStamp baseline, final CancellationToken token)
      throws IOException {
    final TextEncoding encoding = snapshot.encoding();
    encode(snapshot.text(), encoding, null, token);
    final AtomicReference<String> digest = new AtomicReference<String>();
    long recorded = System.currentTimeMillis();
    FileStamp stamp =
        SafeSave.save(
            file,
            baseline,
            new ContentWriter() {
              @Override
              public void write(OutputStream out) throws IOException {
                MessageDigest sha = Digests.sha256();
                OutputStream digesting = new DigestOutputStream(out, sha);
                digesting.write(encoding.bomBytes());
                encode(snapshot.text(), encoding, digesting, token);
                digesting.flush();
                digest.set(Digests.hex(sha));
              }
            },
            token);
    return new FileBaseline(stamp, digest.get(), recorded);
  }

  /**
   * Encodes a text block by block, to {@code out} or, when it is null, only to check every char.
   */
  private static void encode(
      TextSnapshot text, TextEncoding encoding, OutputStream out, CancellationToken token)
      throws IOException {
    CharsetEncoder encoder =
        encoding
            .charset()
            .newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
    TextCursor cursor = text.cursor(0);
    CharBuffer in = CharBuffer.allocate(BLOCK_CHARS);
    ByteBuffer bytes = ByteBuffer.allocate(BLOCK_CHARS * 4);
    long consumed = 0;
    boolean end = false;
    while (true) {
      token.check();
      while (in.hasRemaining() && cursor.hasNext()) {
        in.put(cursor.next());
      }
      end = !cursor.hasNext();
      in.flip();
      int before = in.position();
      CoderResult result = encoder.encode(in, bytes, end);
      if (result.isError()) {
        throw new TextCodingException(
            "Cannot encode", consumed + in.position(), encoding.charset().name());
      }
      consumed += in.position() - before;
      in.compact();
      drain(bytes, out);
      if (end && result.isUnderflow()) {
        break;
      }
    }
    while (encoder.flush(bytes).isOverflow()) {
      drain(bytes, out);
    }
    drain(bytes, out);
  }

  private static void drain(ByteBuffer bytes, OutputStream out) throws IOException {
    bytes.flip();
    if (out != null) {
      out.write(bytes.array(), 0, bytes.limit());
    }
    bytes.clear();
  }
}
