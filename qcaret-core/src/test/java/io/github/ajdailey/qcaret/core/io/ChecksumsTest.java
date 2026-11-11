package io.github.ajdailey.qcaret.core.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.LongConsumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

/** Known vectors for every algorithm, file streaming past the buffer, text encoding and cancel. */
class ChecksumsTest {
  @TempDir Path temp;

  private static final LongConsumer NONE =
      new LongConsumer() {
        @Override
        public void accept(long value) {
          // No progress.
        }
      };

  private static String text(ChecksumAlgorithm algorithm, String text, Charset charset)
      throws Exception {
    Checksums.TextDigester digester = new Checksums.TextDigester(charset, algorithm.newDigest());
    digester.add(text, 0, text.length());
    return digester.finish();
  }

  @Test
  void knownVectors() throws Exception {
    String[][] vectors = {
      {"MD5", "", "d41d8cd98f00b204e9800998ecf8427e"},
      {"MD5", "abc", "900150983cd24fb0d6963f7d28e17f72"},
      {"SHA_1", "", "da39a3ee5e6b4b0d3255bfef95601890afd80709"},
      {"SHA_1", "abc", "a9993e364706816aba3e25717850c26c9cd0d89d"},
      {"SHA_256", "", "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"},
      {"SHA_256", "abc", "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"},
      {
        "SHA_512",
        "",
        "cf83e1357eefb8bdf1542850d66d8007d620e4050b5715dc83f4a921d36ce9ce"
            + "47d0d13c5d85f2b0ff8318d2877eec2f63b931bd47417a81a538327af927da3e"
      },
      {
        "SHA_512",
        "abc",
        "ddaf35a193617abacc417349ae20413112e6fa4e89a97ea20a9eeee64b55d39a"
            + "2192992a274fc1a836ba3c23a3feebbd454d4423643ce80e2a9ac94fa54ca49f"
      }
    };
    for (String[] v : vectors) {
      ChecksumAlgorithm algorithm = ChecksumAlgorithm.valueOf(v[0]);
      assertEquals(v[2], text(algorithm, v[1], StandardCharsets.UTF_8), v[0] + " " + v[1]);
      assertEquals(algorithm.hexLength(), v[2].length());
      Path file = temp.resolve(v[0] + v[1].length());
      Files.writeString(file, v[1], StandardCharsets.US_ASCII);
      assertEquals(
          v[2], Checksums.ofFile(file, algorithm.newDigest(), new CancellationToken(), NONE));
    }
  }

  /** The million-"a" vector spans 16 file buffers and 123 text buffer fills. */
  @Test
  void aMillionAsStreamThroughEveryBuffer() throws Exception {
    String expected = "cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0";
    String text = "a".repeat(1_000_000);
    assertTrue(text.length() > Checksums.BUFFER_BYTES);
    assertTrue(text.length() > Checksums.TextDigester.BUFFER_CHARS);
    assertEquals(expected, text(ChecksumAlgorithm.SHA_256, text, StandardCharsets.UTF_8));
    Path file = temp.resolve("a.bin");
    Files.writeString(file, text, StandardCharsets.US_ASCII);
    final List<Long> progress = new ArrayList<Long>();
    LongConsumer record =
        new LongConsumer() {
          @Override
          public void accept(long value) {
            progress.add(value);
          }
        };
    assertEquals(
        expected,
        Checksums.ofFile(
            file, ChecksumAlgorithm.SHA_256.newDigest(), new CancellationToken(), record));
    assertEquals(1_000_000L, progress.get(progress.size() - 1));
  }

  /** A file of every byte value, 3 buffers and 7 bytes long, digests as the whole array does. */
  @Test
  void aGeneratedFileMatchesTheWholeArray() throws Exception {
    byte[] bytes = new byte[3 * Checksums.BUFFER_BYTES + 7];
    for (int i = 0; i < bytes.length; i++) {
      bytes[i] = (byte) (i * 31 + i / 251);
    }
    Path file = temp.resolve("generated.bin");
    Files.write(file, bytes);
    for (ChecksumAlgorithm algorithm : ChecksumAlgorithm.values()) {
      MessageDigest whole = algorithm.newDigest();
      assertEquals(
          HexFormat.of().formatHex(whole.digest(bytes)),
          Checksums.ofFile(file, algorithm.newDigest(), new CancellationToken(), NONE),
          algorithm.name());
    }
  }

  @Test
  void aCancelStopsMidFile() throws Exception {
    Path file = temp.resolve("big.bin");
    Files.write(file, new byte[4 * Checksums.BUFFER_BYTES]);
    final CancellationToken token = new CancellationToken();
    final List<Long> progress = new ArrayList<Long>();
    final LongConsumer cancelAfterFirst =
        new LongConsumer() {
          @Override
          public void accept(long value) {
            progress.add(value);
            token.cancel();
          }
        };
    assertThrows(
        CancellationException.class,
        new Executable() {
          @Override
          public void execute() throws Throwable {
            Checksums.ofFile(file, ChecksumAlgorithm.MD5.newDigest(), token, cancelAfterFirst);
          }
        });
    assertEquals(List.of((long) Checksums.BUFFER_BYTES), progress);
  }

  /** A surrogate pair split between two pieces at a buffer edge encodes as one code point. */
  @Test
  void textInUtf16AcrossPiecesAndBuffers() throws Exception {
    StringBuilder b = new StringBuilder();
    while (b.length() < Checksums.TextDigester.BUFFER_CHARS - 1) {
      b.append('x');
    }
    b.append("😀\r\nend");
    String text = b.toString();
    Charset charset = StandardCharsets.UTF_16LE;
    Checksums.TextDigester digester =
        new Checksums.TextDigester(charset, ChecksumAlgorithm.SHA_256.newDigest());
    int split = Checksums.TextDigester.BUFFER_CHARS;
    digester.add(text, 0, split);
    digester.add(text, split, text.length());
    MessageDigest whole = ChecksumAlgorithm.SHA_256.newDigest();
    assertEquals(HexFormat.of().formatHex(whole.digest(text.getBytes(charset))), digester.finish());
  }

  @Test
  void aCharTheCharsetCannotEncodeFails() throws Exception {
    final Checksums.TextDigester digester =
        new Checksums.TextDigester(
            StandardCharsets.ISO_8859_1, ChecksumAlgorithm.SHA_256.newDigest());
    assertThrows(
        CharacterCodingException.class,
        new Executable() {
          @Override
          public void execute() throws Throwable {
            digester.add("café €", 0, 6);
            digester.finish();
          }
        });
  }
}
