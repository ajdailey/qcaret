package io.github.ajdailey.qcaret.app.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.core.document.Document;
import io.github.ajdailey.qcaret.core.document.UndoLimits;
import io.github.ajdailey.qcaret.core.text.LineEnding;
import io.github.ajdailey.qcaret.core.text.TextEncoding;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

/** The recovery file format: text of any size and content round trips; damage is reported. */
class RecoveryFileTest {
  @TempDir Path temp;

  private static RecoveryFile.Header header() {
    return new RecoveryFile.Header(
        "b1",
        "/mock/odd\nname\\.txt",
        "",
        "2026-11-05T10:00:00Z",
        "2026-11-05T10:00:30Z",
        7,
        "ISO-8859-1",
        false,
        "CRLF",
        12,
        "2026-11-05T09:00:00Z",
        "ab".repeat(32),
        "java",
        "5,9;1,1",
        "");
  }

  private Path write(String text) throws IOException {
    Document document =
        Document.of(
            text,
            TextEncoding.of(StandardCharsets.UTF_8, false),
            LineEnding.LF,
            UndoLimits.DEFAULTS);
    Path file = temp.resolve("b1.qcr");
    RecoveryFile.write(file, header(), document.text());
    return file;
  }

  private void assertRoundTrips(String text, String bodyEncoding) throws IOException {
    Path file = write(text);
    RecoveryFile.Loaded loaded = RecoveryFile.load(file);
    assertEquals(text.length(), loaded.text().length());
    assertTrue(text.contentEquals(loaded.text()));
    assertEquals(bodyEncoding, loaded.header().bodyEncoding());
    assertEquals(header().path(), loaded.header().path());
    assertEquals(header().selection(), loaded.header().selection());
    assertEquals(12, loaded.header().baseSize());
    assertEquals(bodyEncoding, RecoveryFile.check(file).bodyEncoding());
  }

  @Test
  void anEmptyTextRoundTrips() throws IOException {
    assertRoundTrips("", RecoveryFile.UTF_8);
  }

  @Test
  void aTextEndingInBreaksRoundTrips() throws IOException {
    assertRoundTrips("mock\r\nEND mock\n\n", RecoveryFile.UTF_8);
  }

  @Test
  void aTextLargerThanEveryBufferRoundTrips() throws IOException {
    StringBuilder text = new StringBuilder();
    while (text.length() < 3 << 20) {
      text.append("mock line 世😀 ").append(text.length()).append('\n');
    }
    assertRoundTrips(text.toString(), RecoveryFile.UTF_8);
  }

  @Test
  void anUnpairedSurrogateSwitchesTheBodyToUtf16() throws IOException {
    assertRoundTrips("a\ud83db\n", RecoveryFile.UTF_16BE);
    assertRoundTrips("low \ude00 first", RecoveryFile.UTF_16BE);
    assertRoundTrips("ends high \ud83d", RecoveryFile.UTF_16BE);
  }

  @Test
  void aLargeTextWithAnUnpairedSurrogateRoundTrips() throws IOException {
    StringBuilder text = new StringBuilder("\ud83d");
    while (text.length() < 1 << 21) {
      text.append("mock line\n");
    }
    assertRoundTrips(text.toString(), RecoveryFile.UTF_16BE);
  }

  private void assertDamaged(final Path file) {
    assertThrows(
        RecoveryFile.DamagedException.class,
        new Executable() {
          @Override
          public void execute() throws IOException {
            RecoveryFile.check(file);
          }
        });
    assertThrows(
        RecoveryFile.DamagedException.class,
        new Executable() {
          @Override
          public void execute() throws IOException {
            RecoveryFile.load(file);
          }
        });
  }

  @Test
  void aChangedBodyIsDamaged() throws IOException {
    Path file = write("mock text\n");
    byte[] bytes = Files.readAllBytes(file);
    bytes[bytes.length - 75] ^= 1;
    Files.write(file, bytes);
    assertDamaged(file);
  }

  @Test
  void aTruncatedFileIsDamaged() throws IOException {
    Path file = write("mock text\n");
    byte[] bytes = Files.readAllBytes(file);
    Files.write(file, java.util.Arrays.copyOf(bytes, bytes.length - 3));
    assertDamaged(file);
    Files.write(file, java.util.Arrays.copyOf(bytes, 10));
    assertDamaged(file);
  }

  @Test
  void anotherFileIsDamaged() throws IOException {
    Path file = temp.resolve("b2.qcr");
    Files.writeString(file, "QCARET-RECOVERY 9\nid=b2\n\nbody");
    assertDamaged(file);
  }
}
