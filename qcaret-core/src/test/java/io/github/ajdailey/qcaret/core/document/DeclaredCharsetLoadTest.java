package io.github.ajdailey.qcaret.core.document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.text.DetectionReason;
import io.github.ajdailey.qcaret.core.text.LineEnding;
import io.github.ajdailey.qcaret.core.text.Modeline;
import io.github.ajdailey.qcaret.core.text.TextEncoding;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DeclaredCharsetLoadTest {
  @TempDir Path dir;

  /** A file over three load blocks: Latin-1 text, a modeline in its last line. */
  private Path latin1WithModelineAtEnd() throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] line = "café au lait\n".getBytes(StandardCharsets.ISO_8859_1);
    while (out.size() < 3 * (1 << 20) + 17) {
      out.write(line);
    }
    out.write("# vim: set fenc=latin1 ts=3 :\n".getBytes(StandardCharsets.US_ASCII));
    Path file = dir.resolve("big.txt");
    Files.write(file, out.toByteArray());
    return file;
  }

  @Test
  void aModelineAtTheEndOfALargeFileDeclaresItsCharset() throws IOException {
    Path file = latin1WithModelineAtEnd();
    LoadedFile read =
        DocumentFiles.load(
            file, LoadOptions.DEFAULTS.withDeclarations(null, true), new CancellationToken());
    assertEquals(DetectionReason.DECLARED, read.detection().reason());
    assertEquals(StandardCharsets.ISO_8859_1, read.detection().encoding().charset());
    assertTrue(read.roundTrips());
    Modeline m = TextModelines.read(read.document().text());
    assertEquals(3, m.tabWidth());
    assertEquals(StandardCharsets.ISO_8859_1, m.charset());

    LoadedFile plain = DocumentFiles.load(file, LoadOptions.DEFAULTS, new CancellationToken());
    assertEquals(DetectionReason.FALLBACK, plain.detection().reason());
    assertEquals("windows-1252", plain.detection().encoding().charset().name());
  }

  @Test
  void aConfiguredCharsetLoadsWhenItDecodesAndABomWins() throws IOException {
    Path file = dir.resolve("a.txt");
    Files.write(file, "café\n".getBytes(StandardCharsets.ISO_8859_1));
    LoadOptions configured =
        LoadOptions.DEFAULTS.withDeclarations(StandardCharsets.ISO_8859_1, true);
    LoadedFile read = DocumentFiles.load(file, configured, new CancellationToken());
    assertEquals(DetectionReason.CONFIGURED, read.detection().reason());

    Files.write(file, new byte[] {(byte) 0xef, (byte) 0xbb, (byte) 0xbf, 'a', '\n'});
    read = DocumentFiles.load(file, configured, new CancellationToken());
    assertEquals(DetectionReason.BOM, read.detection().reason());
    assertEquals(StandardCharsets.UTF_8, read.detection().encoding().charset());
    assertTrue(read.detection().encoding().bom());
  }

  @Test
  void modelinesOfADocumentReadOnlyItsEnds() {
    Document d =
        Document.of(
            "# vim: et sw=2\n" + "x\n".repeat(100_000) + "# vim: ff=dos\n",
            TextEncoding.of(StandardCharsets.UTF_8, false),
            LineEnding.LF,
            UndoLimits.DEFAULTS);
    Modeline m = TextModelines.read(d.text());
    assertEquals(2, m.tabWidth());
    assertEquals(Boolean.TRUE, m.insertSpaces());
    assertEquals(LineEnding.CRLF, m.lineEnding());
  }
}
