package io.github.ajdailey.qcaret.core.edit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.document.EditBatch;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/** A conversion over each non-empty selection: all or none, ranges kept, one undo step. */
class CodecEditsTest {
  private static final CancellationToken TOKEN = new CancellationToken();

  private static String convert(String marked, TextCodec codec) throws CodecException {
    Marked m = new Marked(marked);
    return m.apply(CodecEdits.convert(m.document.text(), m.model.selections(), codec, TOKEN))
        .marked();
  }

  @Test
  void eachRangeConvertsAndStaysSelectedWhileCaretsMapThrough() throws CodecException {
    assertEquals("[YWI=] |x [Y2Q=]*", convert("[ab] |x [cd]*", TextCodec.BASE64_ENCODE));
    assertEquals(">YWI=< x", convert(">ab< x", TextCodec.BASE64_ENCODE));
    assertEquals("[a b] |", convert("[a%20b] |", TextCodec.URL_DECODE));
  }

  @Test
  void aCrLfAndASupplementaryCharConvertAsBytes() throws CodecException {
    assertEquals("x[YQ0KYg==]\r\n", convert("x[a\r\nb]\r\n", TextCodec.BASE64_ENCODE));
    assertEquals("x[a\r\nb]\r\n", convert("x[YQ0K\r\nYg==]\r\n", TextCodec.BASE64_DECODE));
    assertEquals("[%F0%9F%98%80]", convert("[😀]", TextCodec.URL_ENCODE));
  }

  @Test
  void oneRefusedRangeLeavesEveryRangeAndNamesItself() {
    final Marked m = new Marked("[YQ==] [!!] [Yg==]");
    CodecException e =
        assertThrows(
            CodecException.class,
            new Executable() {
              @Override
              public void execute() throws CodecException {
                CodecEdits.convert(
                    m.document.text(), m.model.selections(), TextCodec.BASE64_DECODE, TOKEN);
              }
            });
    assertEquals(CodecException.Reason.NOT_BASE64, e.reason());
    assertEquals(2, e.selection());
    assertEquals("[YQ==] [!!] [Yg==]", m.marked());
  }

  @Test
  void caretsAloneAndUnchangedRangesGiveNoEdit() throws CodecException {
    Marked carets = new Marked("a|b|");
    assertFalse(CodecEdits.hasRange(carets.model.selections()));
    assertNull(
        CodecEdits.convert(
            carets.document.text(), carets.model.selections(), TextCodec.URL_ENCODE, TOKEN));
    Marked plain = new Marked("[abc] [d-e] |");
    assertTrue(CodecEdits.hasRange(plain.model.selections()));
    assertNull(
        CodecEdits.convert(
            plain.document.text(), plain.model.selections(), TextCodec.URL_ENCODE, TOKEN));
  }

  @Test
  void aWhitespaceRangeDecodesToNothing() throws CodecException {
    assertEquals("a|b", convert("a[ \t\r\n]b", TextCodec.BASE64_DECODE));
  }

  @Test
  void theConversionIsOneCommandStep() throws CodecException {
    Marked m = new Marked("[\"a\"] [\"b\"]");
    Edit edit =
        CodecEdits.convert(m.document.text(), m.model.selections(), TextCodec.JSON_ESCAPE, TOKEN);
    assertEquals(EditBatch.Kind.COMMAND, edit.batch().kind());
    m.apply(edit);
    assertEquals("[\\\"a\\\"] [\\\"b\\\"]", m.marked());
    m.document.undo(m.model);
    assertEquals("[\"a\"] [\"b\"]", m.marked());
  }

  @Test
  void aLongRangeConverts() throws CodecException {
    String text = "xé ".repeat(TextCodec.CHECK_CHARS);
    Marked m = new Marked("[" + text + "]");
    m.apply(
        CodecEdits.convert(m.document.text(), m.model.selections(), TextCodec.URL_ENCODE, TOKEN));
    m.apply(
        CodecEdits.convert(m.document.text(), m.model.selections(), TextCodec.URL_DECODE, TOKEN));
    assertEquals("[" + text + "]", m.marked());
  }

  @Test
  void aCanceledTokenStopsTheWork() {
    final Marked m = new Marked("[ab]");
    final CancellationToken canceled = new CancellationToken();
    canceled.cancel();
    assertThrows(
        CancellationException.class,
        new Executable() {
          @Override
          public void execute() throws CodecException {
            CodecEdits.convert(
                m.document.text(), m.model.selections(), TextCodec.XML_ESCAPE, canceled);
          }
        });
  }
}
