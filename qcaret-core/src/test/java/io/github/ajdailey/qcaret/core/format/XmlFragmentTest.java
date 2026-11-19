package io.github.ajdailey.qcaret.core.format;

import static io.github.ajdailey.qcaret.core.format.FormatFixture.TWO;
import static io.github.ajdailey.qcaret.core.format.FormatFixture.assertWhitespaceOnly;
import static io.github.ajdailey.qcaret.core.format.FormatFixture.formatSelections;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.document.Selection;
import io.github.ajdailey.qcaret.core.document.SelectionSet;
import io.github.ajdailey.qcaret.core.format.Formats.Language;
import io.github.ajdailey.qcaret.core.format.Formats.Mode;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A selection holding several top-level XML items is parsed as a fragment inside a synthetic root
 * and laid out at the selection's base indent; the whole text must still be one document.
 */
class XmlFragmentTest {
  /** Runs on the selection from {@code from} to the end of the text. */
  private static FormatResult run(Mode mode, String text, int from) {
    return Formats.run(
        Language.XML,
        mode,
        FormatFixture.document(text).text(),
        SelectionSet.of(List.of(Selection.of(from, text.length())), 0),
        TWO,
        new CancellationToken());
  }

  private static String format(Mode mode, String text, int from) {
    String out = formatSelections(Language.XML, mode, text, TWO, Selection.of(from, text.length()));
    assertWhitespaceOnly(Language.XML, text, out);
    return out;
  }

  @Test
  void siblingElementsGoOnePerLineAtTheBaseIndent() {
    String text = "<r>\n  <a><b/></a><c/>";
    int from = text.indexOf("<a>");
    String laid = "<r>\n  <a>\n    <b/>\n  </a>\n  <c/>";
    assertEquals(laid, format(Mode.PRETTY_PRINT, text, from));
    assertEquals(text, format(Mode.MINIFY, laid, from));
  }

  @Test
  void aLeadingCommentIsAnItem() {
    String text = "x\n<!-- c --><a/> <b/>";
    assertEquals("x\n<!-- c -->\n<a/>\n<b/>", format(Mode.PRETTY_PRINT, text, 2));
    assertEquals("x\n<!-- c --><a/><b/>", format(Mode.MINIFY, text, 2));
  }

  @Test
  void textBetweenItemsMakesTheFragmentMixedContent() {
    String text = "<a> <x/> </a> text <b>\n<c/></b>";
    assertNull(run(Mode.PRETTY_PRINT, text, 0).batch());
    assertNull(run(Mode.MINIFY, text, 0).batch());
    assertNull(run(Mode.MINIFY, text, 0).error());
  }

  @Test
  void checkNamesAFragment() {
    FormatResult result = run(Mode.CHECK, "<a/><b/>", 0);
    assertNull(result.error());
    assertTrue(result.fragment());
    FormatResult single = run(Mode.CHECK, "<!-- c --><a><b/></a>", 0);
    assertNull(single.error());
    assertFalse(single.fragment());
  }

  @Test
  void aSingleElementSelectionIsADocument() {
    String text = "<r>\n  <a><b/></a>\n</r>";
    int from = text.indexOf("<a>");
    String out =
        formatSelections(
            Language.XML, Mode.PRETTY_PRINT, text, TWO, Selection.of(from, text.indexOf("\n</r>")));
    assertEquals("<r>\n  <a>\n    <b/>\n  </a>\n</r>", out);
    assertFalse(run(Mode.CHECK, "<!DOCTYPE a><a/>", 0).fragment());
    assertNull(run(Mode.CHECK, "<!DOCTYPE a><a/>", 0).error());
  }

  @Test
  void anErrorInTheSecondElementMapsToTheText() {
    String text = "abc <a/><b></c>";
    FormatError e = run(Mode.CHECK, text, 4).error();
    assertEquals(1, e.line());
    assertEquals(text.indexOf("</c>") + 2, e.offset());
    String lines = "abc\n <a/>\n<b></c>";
    FormatError second = run(Mode.PRETTY_PRINT, lines, 5).error();
    assertEquals(3, second.line());
    assertEquals(lines.indexOf("</c>") + 2, second.offset());
  }

  @Test
  void anUnclosedElementFailsAtTheSelectionEnd() {
    String text = "<a/><b>";
    FormatError e = run(Mode.CHECK, text, 0).error();
    assertEquals(text.length(), e.offset());
  }

  @Test
  void aDeclarationOrDoctypeInAFragmentIsAnError() {
    String doctype = "<a/><!DOCTYPE a><b/>";
    FormatError e = run(Mode.PRETTY_PRINT, doctype, 0).error();
    assertEquals(4, e.offset());
    assertEquals("An XML fragment cannot hold a DOCTYPE", e.reason());
    String declaration = "<a x='<?xml '/><!-- <?xml --><?xml version=\"1.0\"?><b/>";
    FormatError d = run(Mode.CHECK, declaration, 0).error();
    assertEquals(declaration.indexOf("<?xml version"), d.offset());
    assertEquals("An XML fragment cannot hold an XML declaration", d.reason());
    assertNull(run(Mode.CHECK, "<a/><?xml-stylesheet href='s'?><b/>", 0).error());
  }

  @Test
  void theWholeTextIsStillOneDocument() {
    FormatResult result =
        Formats.run(
            Language.XML,
            Mode.CHECK,
            FormatFixture.document("<a/><b/>").text(),
            SelectionSet.caret(0),
            TWO,
            new CancellationToken());
    assertEquals(5, result.error().offset());
    assertFalse(result.fragment());
  }
}
