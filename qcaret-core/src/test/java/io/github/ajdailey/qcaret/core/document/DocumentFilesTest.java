package io.github.ajdailey.qcaret.core.document;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.io.FileStamp;
import io.github.ajdailey.qcaret.core.text.DetectionReason;
import io.github.ajdailey.qcaret.core.text.LineEnding;
import io.github.ajdailey.qcaret.core.text.TextEncoding;
import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

class DocumentFilesTest {
  private static final String TEXT = "café 中\r\nline two\nthree\rlast";

  @TempDir Path temp;

  private static byte[] concat(byte[] a, byte[] b) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.writeBytes(a);
    out.writeBytes(b);
    return out.toByteArray();
  }

  private LoadedFile load(Path file, LoadOptions options) throws Exception {
    return DocumentFiles.load(file, options, new CancellationToken());
  }

  /** Every BOM and mixed line endings come back byte for byte after an edit is undone. */
  @Test
  void openAndSaveRoundTripTheBytes() throws Exception {
    List<TextEncoding> encodings =
        List.of(
            TextEncoding.of(StandardCharsets.UTF_8, false),
            TextEncoding.of(StandardCharsets.UTF_8, true),
            TextEncoding.of(StandardCharsets.UTF_16LE, true),
            TextEncoding.of(StandardCharsets.UTF_16BE, true),
            TextEncoding.of(Charset.forName("UTF-32LE"), true),
            TextEncoding.of(Charset.forName("UTF-32BE"), true));
    for (TextEncoding encoding : encodings) {
      Path file = temp.resolve(encoding.charset().name() + encoding.bom() + ".txt");
      byte[] bytes = concat(encoding.bomBytes(), TEXT.getBytes(encoding.charset()));
      Files.write(file, bytes);
      LoadedFile loaded = load(file, LoadOptions.DEFAULTS);
      Document doc = loaded.document();
      assertEquals(encoding, doc.encoding(), file.toString());
      assertEquals(TEXT, doc.text().toString());
      assertTrue(loaded.roundTrips());
      assertTrue(doc.lineEndings().isMixed());
      assertEquals(LineEnding.CRLF, doc.lineEnding());
      doc.apply(EditBatch.replace(0, 0, "x"));
      doc.undo(null);
      FileStamp saved =
          DocumentFiles.save(file, doc.snapshot(), loaded.stamp(), new CancellationToken());
      assertArrayEquals(bytes, Files.readAllBytes(file), file.toString());
      assertEquals(FileStamp.read(file), saved);
    }
  }

  @Test
  void aLegacyFileUsesTheFallbackAndSavesInIt() throws Exception {
    Path file = temp.resolve("legacy.txt");
    byte[] bytes = "price €5\n".getBytes(Charset.forName("windows-1252"));
    Files.write(file, bytes);
    LoadedFile loaded = load(file, LoadOptions.DEFAULTS);
    assertEquals(DetectionReason.FALLBACK, loaded.detection().reason());
    assertEquals("price €5\n", loaded.document().text().toString());
    DocumentFiles.save(file, loaded.document().snapshot(), loaded.stamp(), new CancellationToken());
    assertArrayEquals(bytes, Files.readAllBytes(file));
  }

  @Test
  void aBinaryFileIsRefusedUnlessAllowed() throws Exception {
    final Path file = temp.resolve("data.bin");
    byte[] bytes = {'P', 'K', 3, 4, 0, (byte) 0xff, 0x7f, (byte) 0x80};
    Files.write(file, bytes);
    assertThrows(
        BinaryFileException.class,
        new Executable() {
          @Override
          public void execute() throws Throwable {
            load(file, LoadOptions.DEFAULTS);
          }
        });
    LoadedFile loaded = load(file, LoadOptions.DEFAULTS.asBinary());
    assertTrue(loaded.binary());
    assertTrue(loaded.roundTrips());
    assertEquals(8, loaded.document().text().length());
    DocumentFiles.save(file, loaded.document().snapshot(), loaded.stamp(), new CancellationToken());
    assertArrayEquals(bytes, Files.readAllBytes(file));
  }

  @Test
  void anUnmappableCharFailsTheSaveBeforeTheFileIsTouched() throws Exception {
    final Path file = temp.resolve("latin.txt");
    Files.write(file, "abc".getBytes(StandardCharsets.ISO_8859_1));
    final LoadedFile loaded =
        load(file, LoadOptions.DEFAULTS.withForced(StandardCharsets.ISO_8859_1));
    Document doc = loaded.document();
    doc.apply(EditBatch.replace(2, 0, "中"));
    final DocumentSnapshot snapshot = doc.snapshot();
    TextCodingException e =
        assertThrows(
            TextCodingException.class,
            new Executable() {
              @Override
              public void execute() throws Throwable {
                DocumentFiles.save(file, snapshot, loaded.stamp(), new CancellationToken());
              }
            });
    assertEquals(2, e.offset());
    assertEquals("ISO-8859-1", e.charset());
    assertEquals("abc", Files.readString(file, StandardCharsets.ISO_8859_1));
  }

  @Test
  void malformedBytesFailAForcedDecode() throws Exception {
    final Path file = temp.resolve("bad.txt");
    Files.write(file, new byte[] {'a', 'b', (byte) 0xc3, 'c'});
    TextCodingException e =
        assertThrows(
            TextCodingException.class,
            new Executable() {
              @Override
              public void execute() throws Throwable {
                load(file, LoadOptions.DEFAULTS.withForced(StandardCharsets.UTF_8));
              }
            });
    assertEquals(2, e.offset());
  }

  /** windows-31j decodes NEC row 89 and IBM row 115 to the same chars and encodes to IBM's. */
  @Test
  void textThatDoesNotEncodeBackIsReported() throws Exception {
    Path file = temp.resolve("sjis.txt");
    Files.write(file, new byte[] {(byte) 0xed, 0x40});
    LoadedFile loaded = load(file, LoadOptions.DEFAULTS.withForced(Charset.forName("windows-31j")));
    assertFalse(loaded.roundTrips());
  }

  @Test
  void aCanceledLoadThrows() throws Exception {
    final Path file = temp.resolve("a.txt");
    Files.writeString(file, "text");
    final CancellationToken token = new CancellationToken();
    token.cancel();
    assertThrows(
        CancellationException.class,
        new Executable() {
          @Override
          public void execute() throws Throwable {
            DocumentFiles.load(file, LoadOptions.DEFAULTS, token);
          }
        });
  }
}
