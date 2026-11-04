package io.github.ajdailey.qcaret.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.document.Document;
import io.github.ajdailey.qcaret.core.document.LoadOptions;
import io.github.ajdailey.qcaret.core.text.LineEnding;
import io.github.ajdailey.qcaret.core.text.TextEncoding;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/** Line-break conversion of every break kind, the caret's new offset and cancellation. */
class LineConversionTest {
  private static Document document(String text) {
    return Document.of(
        text,
        TextEncoding.of(StandardCharsets.UTF_8, false),
        LineEnding.LF,
        LoadOptions.DEFAULTS.limits());
  }

  @Test
  void everyBreakKindBecomesTheEnding() {
    Document doc = document("a\r\nb\nc\rd\r");
    LineConversion lf =
        LineConversion.convert(doc.text(), LineEnding.LF, 7, new CancellationToken());
    assertEquals("a\nb\nc\nd\n", lf.replacement().text());
    assertEquals(0, lf.replacement().offset());
    assertEquals(doc.text().length(), lf.replacement().removeLength());
    // Offset 7 is 'd', after "a\r\nb\nc\r".
    assertEquals(6, lf.caret());
    LineConversion crlf =
        LineConversion.convert(doc.text(), LineEnding.CRLF, 7, new CancellationToken());
    assertEquals("a\r\nb\r\nc\r\nd\r\n", crlf.replacement().text());
    assertEquals(9, crlf.caret());
  }

  @Test
  void aCaretAtTheEndStaysAtTheEnd() {
    Document doc = document("x\r\n");
    LineConversion lf =
        LineConversion.convert(doc.text(), LineEnding.LF, 3, new CancellationToken());
    assertEquals(2, lf.caret());
  }

  @Test
  void aCanceledConversionThrows() {
    final Document doc = document("line\n".repeat(100_000));
    final CancellationToken token = new CancellationToken();
    token.cancel();
    assertThrows(
        CancellationException.class,
        new Executable() {
          @Override
          public void execute() {
            LineConversion.convert(doc.text(), LineEnding.CRLF, 0, token);
          }
        });
  }
}
