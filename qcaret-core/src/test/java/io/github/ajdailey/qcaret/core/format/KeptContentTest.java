package io.github.ajdailey.qcaret.core.format;

import static io.github.ajdailey.qcaret.core.format.FormatFixture.TWO;
import static io.github.ajdailey.qcaret.core.format.FormatFixture.document;
import static io.github.ajdailey.qcaret.core.format.FormatFixture.result;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.document.Selection;
import io.github.ajdailey.qcaret.core.document.SelectionSet;
import io.github.ajdailey.qcaret.core.format.Formats.Language;
import io.github.ajdailey.qcaret.core.format.Formats.Mode;
import java.util.List;
import org.junit.jupiter.api.Test;

/** {@link FormatResult#kept}: why an XML Pretty Print or Minify changed nothing. */
class KeptContentTest {
  /** Runs Pretty Print on the whole text, which must change nothing, and returns the reason. */
  private static KeptContent kept(Mode mode, String text) {
    FormatResult result = result(Language.XML, mode, text, TWO);
    assertNull(result.error(), String.valueOf(result.error()));
    assertNull(result.batch(), "an edit");
    return result.kept();
  }

  private static void assertKept(
      KeptContent kept, KeptContent.Kind kind, String element, int offset, int line, int c) {
    assertNotNull(kept, "a reason");
    assertEquals(new KeptContent(kind, element, offset, line, c), kept);
  }

  @Test
  void textBesideAnElementNamesTheElementAndItsLine() {
    String text = "<?xml version=\"1.0\"?>\n<root>\nhello\n<a>\n<b>x</b>\n</a>\n</root>\n";
    int at = text.indexOf("<root>");
    assertKept(kept(Mode.PRETTY_PRINT, text), KeptContent.Kind.TEXT, "root", at, 2, -1);
    // Minify joins the declaration and the root, so the minified text has none between them.
    String minified = text.replace("?>\n", "?>");
    assertKept(kept(Mode.MINIFY, minified), KeptContent.Kind.TEXT, "root", at - 1, 1, -1);
  }

  @Test
  void theFirstKeptElementInTextOrderIsNamed() {
    String text = "<r>\n  <p:a k='1'>one<b/></p:a>\n  <c>two<d/></c>\n</r>";
    assertKept(
        kept(Mode.PRETTY_PRINT, text), KeptContent.Kind.TEXT, "p:a", text.indexOf("<p:a"), 2, -1);
  }

  @Test
  void cdataBesideAnElementIsNamed() {
    String text = "<root>\n  <a><![CDATA[x]]>\n<b>x</b>\n</a>\n</root>";
    assertKept(
        kept(Mode.PRETTY_PRINT, text), KeptContent.Kind.CDATA, "a", text.indexOf("<a>"), 2, -1);
  }

  @Test
  void anInvisibleCharAsTheOnlyTextIsNamed() {
    String text = "<root>\n\u00a0<a>\n<b>x</b>\n</a>\n</root>";
    assertKept(kept(Mode.PRETTY_PRINT, text), KeptContent.Kind.TEXT, "root", 0, 1, 0xa0);
    String zeroWidth = "<root>\n  <a>\u200b<b/>\u00a0</a>\n</root>";
    assertKept(
        kept(Mode.PRETTY_PRINT, zeroWidth),
        KeptContent.Kind.TEXT,
        "a",
        zeroWidth.indexOf("<a>"),
        2,
        0x200b);
    String visible = "<root>\n\u00a0x<a/>\n</root>";
    assertKept(kept(Mode.PRETTY_PRINT, visible), KeptContent.Kind.TEXT, "root", 0, 1, -1);
  }

  @Test
  void xmlSpacePreserveIsNamed() {
    String text = "<root>\n  <pre xml:space=\"preserve\"><a/>\n<b/></pre>\n</root>";
    assertKept(
        kept(Mode.PRETTY_PRINT, text),
        KeptContent.Kind.PRESERVE,
        "pre",
        text.indexOf("<pre"),
        2,
        -1);
  }

  @Test
  void contentOfTextAndCdataOnlyIsNotAReason() {
    assertNull(kept(Mode.PRETTY_PRINT, "<a>\n  <b> two  words </b>\n  <c><![CDATA[x]]></c>\n</a>"));
    assertNull(kept(Mode.PRETTY_PRINT, "<a>\n  <pre xml:space=\"preserve\"> x </pre>\n</a>"));
    assertNull(kept(Mode.MINIFY, "<a><b>x</b></a>"));
  }

  @Test
  void aSelectionReportsTheFirstSelectionWithAReason() {
    String text = "<r>\n<a>\n  <b/>\n</a>\n<c>x<d/></c>\n<e>y<f/></e>\n</r>";
    int a = text.indexOf("<a>");
    int c = text.indexOf("<c>");
    int e = text.indexOf("<e>");
    FormatResult result =
        Formats.run(
            Language.XML,
            Mode.PRETTY_PRINT,
            document(text).text(),
            SelectionSet.of(
                List.of(
                    Selection.of(a, text.indexOf("</a>") + 4),
                    Selection.of(c, text.indexOf("</c>") + 4),
                    Selection.of(e, text.indexOf("</e>") + 4)),
                0),
            TWO,
            new CancellationToken());
    assertNull(result.batch());
    assertKept(result.kept(), KeptContent.Kind.TEXT, "c", c, 5, -1);
  }

  @Test
  void aFragmentsTopLevelTextIsNamedWithoutAnElement() {
    String text = "<r>\nx <a/>\n</r>";
    int start = text.indexOf('x');
    FormatResult result =
        Formats.run(
            Language.XML,
            Mode.PRETTY_PRINT,
            document(text).text(),
            SelectionSet.of(List.of(Selection.of(start, text.indexOf("</r>"))), 0),
            TWO,
            new CancellationToken());
    assertNull(result.batch());
    assertKept(result.kept(), KeptContent.Kind.TEXT, null, start, 2, -1);
  }

  @Test
  void anEditCheckAndJsonCarryNoReason() {
    String mixed = "<root>x<a><b/></a></root>";
    FormatResult edit =
        result(Language.XML, Mode.PRETTY_PRINT, "<r><a><b>x</b></a><c>y<d/></c></r>", TWO);
    assertNotNull(edit.batch());
    assertNull(edit.kept());
    assertNull(result(Language.XML, Mode.CHECK, mixed, TWO).kept());
    FormatResult json = result(Language.JSON, Mode.PRETTY_PRINT, "{\n  \"a\": 1\n}", TWO);
    assertNull(json.error());
    assertNull(json.batch());
    assertNull(json.kept());
    assertNull(result(Language.JSON, Mode.MINIFY, "[1,2]", TWO).batch());
  }
}
