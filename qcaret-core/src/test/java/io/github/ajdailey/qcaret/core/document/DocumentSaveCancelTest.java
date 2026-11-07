package io.github.ajdailey.qcaret.core.document;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.text.TextEncoding;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

/** A cancel in the third encoder block of a save's write pass, on a file over every block size. */
class DocumentSaveCancelTest {
  private static final byte[] ORIGINAL =
      "0123456789abcde\n".repeat(1 << 18).getBytes(StandardCharsets.ISO_8859_1);

  @TempDir Path temp;

  /** ISO-8859-1 by hand; the third block of the second encoder, the write pass, cancels. */
  private static final class CancelingCharset extends Charset {
    private final CancellationToken token;
    private final AtomicInteger encoders = new AtomicInteger();

    CancelingCharset(CancellationToken token) {
      super("X-CANCELING", null);
      this.token = token;
    }

    @Override
    public boolean contains(Charset other) {
      return false;
    }

    @Override
    public CharsetDecoder newDecoder() {
      return StandardCharsets.ISO_8859_1.newDecoder();
    }

    @Override
    public CharsetEncoder newEncoder() {
      final boolean writing = encoders.incrementAndGet() == 2;
      return new CharsetEncoder(this, 1f, 1f) {
        private int calls;

        @Override
        protected CoderResult encodeLoop(CharBuffer in, ByteBuffer out) {
          if (writing && ++calls == 3) {
            token.cancel();
          }
          while (in.hasRemaining()) {
            if (!out.hasRemaining()) {
              return CoderResult.OVERFLOW;
            }
            out.put((byte) in.get());
          }
          return CoderResult.UNDERFLOW;
        }
      };
    }
  }

  private Path file() throws Exception {
    return Files.write(temp.resolve("a.txt"), ORIGINAL);
  }

  private static DocumentSnapshot edited(LoadedFile loaded, CancellationToken token) {
    Document document = loaded.document();
    document.apply(EditBatch.of(EditBatch.Kind.COMMAND, List.of(new Replacement(0, 0, "edited "))));
    document.setEncoding(new TextEncoding(new CancelingCharset(token), false));
    return document.snapshot();
  }

  /** The truncated target has no old content to go back to, so the write finishes. */
  @Test
  void aCancelDuringAnInPlaceSaveRunsTheWriteToItsEnd() throws Exception {
    assumeTrue(temp.getFileSystem().supportedFileAttributeViews().contains("unix"));
    Path file = file();
    Path other = Files.createLink(temp.resolve("link.txt"), file);
    LoadedFile loaded = DocumentFiles.load(file, LoadOptions.DEFAULTS, new CancellationToken());
    CancellationToken token = new CancellationToken();
    DocumentFiles.save(file, edited(loaded, token), loaded.stamp(), token);
    assertEquals(true, token.isCanceled());
    byte[] expected = new byte[ORIGINAL.length + 7];
    System.arraycopy("edited ".getBytes(StandardCharsets.ISO_8859_1), 0, expected, 0, 7);
    System.arraycopy(ORIGINAL, 0, expected, 7, ORIGINAL.length);
    assertArrayEquals(expected, Files.readAllBytes(file));
    assertArrayEquals(expected, Files.readAllBytes(other));
  }

  @Test
  void aCancelDuringAnAtomicSaveLeavesTheTargetUnchanged() throws Exception {
    final Path file = file();
    final LoadedFile loaded =
        DocumentFiles.load(file, LoadOptions.DEFAULTS, new CancellationToken());
    final CancellationToken token = new CancellationToken();
    final DocumentSnapshot snapshot = edited(loaded, token);
    assertThrows(
        CancellationException.class,
        new Executable() {
          @Override
          public void execute() throws Throwable {
            DocumentFiles.save(file, snapshot, loaded.stamp(), token);
          }
        });
    assertArrayEquals(ORIGINAL, Files.readAllBytes(file));
  }

  @Test
  void aCancelBeforeAnInPlaceSaveLeavesTheTargetUnchanged() throws Exception {
    assumeTrue(temp.getFileSystem().supportedFileAttributeViews().contains("unix"));
    final Path file = file();
    Path other = Files.createLink(temp.resolve("link.txt"), file);
    final LoadedFile loaded =
        DocumentFiles.load(file, LoadOptions.DEFAULTS, new CancellationToken());
    final CancellationToken token = new CancellationToken();
    final DocumentSnapshot snapshot = edited(loaded, new CancellationToken());
    token.cancel();
    assertThrows(
        CancellationException.class,
        new Executable() {
          @Override
          public void execute() throws Throwable {
            DocumentFiles.save(file, snapshot, loaded.stamp(), token);
          }
        });
    assertArrayEquals(ORIGINAL, Files.readAllBytes(other));
  }
}
