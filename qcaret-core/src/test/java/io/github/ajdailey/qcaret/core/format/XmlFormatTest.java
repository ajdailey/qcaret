package io.github.ajdailey.qcaret.core.format;

import static io.github.ajdailey.qcaret.core.format.FormatFixture.TWO;
import static io.github.ajdailey.qcaret.core.format.FormatFixture.error;
import static io.github.ajdailey.qcaret.core.format.FormatFixture.format;
import static io.github.ajdailey.qcaret.core.format.FormatFixture.formatSelections;
import static io.github.ajdailey.qcaret.core.format.FormatFixture.result;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.document.Document;
import io.github.ajdailey.qcaret.core.document.Selection;
import io.github.ajdailey.qcaret.core.document.SelectionSet;
import io.github.ajdailey.qcaret.core.format.Formats.Language;
import io.github.ajdailey.qcaret.core.format.Formats.Mode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

/** XML Pretty Print, Minify and Check through {@link Formats}. */
class XmlFormatTest {
  private static String pretty(String text) {
    return format(Language.XML, Mode.PRETTY_PRINT, text, TWO);
  }

  private static String minify(String text) {
    return format(Language.XML, Mode.MINIFY, text, TWO);
  }

  @Test
  void prettyPrintPutsOneElementPerLineAndMinifyUndoesIt() {
    String flat = "<a><b>x</b><c/><d><e k='1'/></d></a>";
    String laid = "<a>\n  <b>x</b>\n  <c/>\n  <d>\n    <e k='1'/>\n  </d>\n</a>";
    assertEquals(laid, pretty(flat));
    assertEquals(flat, minify(laid));
    assertEquals(laid, pretty(laid));
  }

  @Test
  void attributeSpacingAndTextOnlyContentStay() {
    assertEquals(
        "<a  x = \"1\"\n   y='>2' >\n  <b> two  words </b>\n</a>",
        pretty("<a  x = \"1\"\n   y='>2' ><b> two  words </b></a>"));
  }

  @Test
  void anElementWithOnlyWhitespaceBecomesEmpty() {
    assertEquals("<a>\n  <b></b>\n</a>", pretty("<a><b>  \n </b></a>"));
    assertEquals("<a><b></b></a>", minify("<a> <b> </b> </a>"));
  }

  @Test
  void prologDoctypeCommentsPisAndCdataStayVerbatim() {
    String in =
        "<?xml version=\"1.0\"?><!DOCTYPE a [\n <!ENTITY e \"]>\">\n <!-- ]> -->\n]>"
            + "<!--  c  --><a><?pi  x ?><s><![CDATA[ <x> ]]></s></a>";
    assertEquals(
        "<?xml version=\"1.0\"?>\n<!DOCTYPE a [\n <!ENTITY e \"]>\">\n <!-- ]> -->\n]>\n"
            + "<!--  c  -->\n<a>\n  <?pi  x ?>\n  <s><![CDATA[ <x> ]]></s>\n</a>",
        pretty(in));
  }

  @Test
  void mixedContentStaysVerbatimToItsEnd() {
    String p = "<p>Hi <b><i>x</i> </b>there<q><r/></q></p>";
    assertEquals("<d>\n  " + p + "\n  <e/>\n</d>", pretty("<d>" + p + "<e/></d>"));
    assertEquals("<d>" + p + "<e/></d>", minify("<d>\n" + p + "\n<e/></d>"));
  }

  @Test
  void preserveKeepsItsSubtreeAndDefaultResets() {
    String preserved = "<s xml:space=\"preserve\"> <t> <u/> </t> </s>";
    assertEquals("<r>\n  " + preserved + "\n</r>", pretty("<r>" + preserved + "</r>"));
    assertEquals(
        "<s xml:space='preserve'> <d xml:space=\"default\">\n    <e/>\n  </d> </s>",
        pretty("<s xml:space='preserve'> <d xml:space=\"default\"><e/></d> </s>"));
    assertEquals("<r>" + preserved + "</r>", minify("<r>\n  " + preserved + "\n</r>"));
  }

  @Test
  void edgesStayAndTheDocumentLineEndingIsUsed() {
    FormatLayout crlf = new FormatLayout("\t", "\r\n", false, 1 << 20);
    assertEquals(
        "\r\n<a>\r\n\t<b/>\r\n</a>\r\n",
        format(Language.XML, Mode.PRETTY_PRINT, "\r\n<a>\r\n<b/></a>\r\n", crlf));
    FormatLayout cr = new FormatLayout("  ", "\r", false, 1 << 20);
    assertEquals(
        "<a>\r  <b/>\r</a>\r", format(Language.XML, Mode.PRETTY_PRINT, "<a>\r<b/>\r\r</a>\r", cr));
  }

  @Test
  void aSelectionIndentsFromItsFirstLine() {
    String text = "<r>\n    <x><y/></x>\n</r>";
    int start = text.indexOf("<x>");
    int end = text.indexOf("\n</r>");
    assertEquals(
        "<r>\n    <x>\n      <y/>\n    </x>\n</r>",
        formatSelections(Language.XML, Mode.PRETTY_PRINT, text, TWO, Selection.of(start, end)));
  }

  @Test
  void eachSelectionFormatsAloneAndKeepsItsEnds() {
    String text = "<x><y/></x> | <z><w/></z>";
    Document document = FormatFixture.document(text);
    SelectionSet set =
        SelectionSet.of(List.of(Selection.of(0, 11), Selection.of(14, text.length())), 1);
    FormatResult result =
        Formats.run(
            Language.XML, Mode.PRETTY_PRINT, document.text(), set, TWO, new CancellationToken());
    document.apply(result.batch());
    String out = document.text().toString();
    assertEquals("<x>\n  <y/>\n</x> | <z>\n  <w/>\n</z>", out);
    assertEquals(
        List.of(Selection.of(0, 15), Selection.of(18, out.length())), result.after().selections());
    assertEquals(1, result.after().primaryIndex());
  }

  @Test
  void aFailingSelectionFailsTheRun() {
    String text = "<x><y/></x> | <z>";
    FormatResult result =
        Formats.run(
            Language.XML,
            Mode.PRETTY_PRINT,
            FormatFixture.document(text).text(),
            SelectionSet.of(List.of(Selection.of(0, 11), Selection.of(14, 17)), 0),
            TWO,
            new CancellationToken());
    assertNull(result.batch());
    assertEquals(1, result.error().line());
    assertTrue(result.error().offset() >= 14, String.valueOf(result.error()));
  }

  @Test
  void aCaretInAGapLandsBeforeTheNextToken() {
    String text = "<a> <b/></a>";
    Document document = FormatFixture.document(text);
    FormatResult result =
        Formats.run(
            Language.XML,
            Mode.PRETTY_PRINT,
            document.text(),
            SelectionSet.of(List.of(Selection.caret(3), Selection.caret(6)), 0),
            TWO,
            new CancellationToken());
    assertEquals(List.of(Selection.caret(6), Selection.caret(8)), result.after().selections());
  }

  @Test
  void checkNamesTheLineAndColumnOfTheFirstError() {
    assertNull(result(Language.XML, Mode.CHECK, "<a>\n<b/></a>", TWO).error());
    assertNull(result(Language.XML, Mode.CHECK, "<a>\n<b/></a>", TWO).batch());
    FormatError e = error(Language.XML, Mode.CHECK, "<a>\n  <b>x</c></a>", TWO);
    assertEquals(2, e.line());
    assertEquals("<a>\n  <b>x</".length(), e.offset());
    assertTrue(e.reason().contains("</b>"), e.reason());
    FormatError pretty = error(Language.XML, Mode.PRETTY_PRINT, "<a>\n  <b>x</c></a>", TWO);
    assertEquals(e, pretty);
  }

  @Test
  void aSupplementaryCharCountsTwoColumns() {
    FormatError e = error(Language.XML, Mode.CHECK, "<a>\r\n😀x&</a>", TWO);
    assertEquals(2, e.line());
    assertEquals(5, e.column());
    assertEquals("<a>\r\n😀x&".length(), e.offset());
  }

  @Test
  void aSelectionErrorMapsToTheDocument() {
    String text = "first\n  <a>\n<b></a>";
    FormatResult result =
        Formats.run(
            Language.XML,
            Mode.CHECK,
            FormatFixture.document(text).text(),
            SelectionSet.of(List.of(Selection.of(8, text.length())), 0),
            TWO,
            new CancellationToken());
    assertEquals(3, result.error().line());
    assertEquals(text.indexOf("</a>") + 2, result.error().offset());
  }

  @Test
  void emptyTextAndTwoRootsAreErrors() {
    assertEquals(1, error(Language.XML, Mode.CHECK, "", TWO).line());
    assertEquals(4, error(Language.XML, Mode.CHECK, "<a/><b/>", TWO).offset() - 1);
  }

  @Test
  void anInternalSubsetIsReadAndAnUndeclaredEntityFails() {
    assertNull(
        result(Language.XML, Mode.CHECK, "<!DOCTYPE a [<!ENTITY e 'x'>]><a>&e;</a>", TWO).error());
    assertTrue(error(Language.XML, Mode.CHECK, "<a>&f;</a>", TWO).reason().contains("\"f\""));
  }

  @Test
  void externalFilesAreNeverRead(@TempDir Path dir) throws IOException {
    // Each file would make the text malformed if the parser read it.
    Path dtd = dir.resolve("bad.dtd");
    Files.writeString(dtd, "<!ELEMENT", StandardCharsets.UTF_8);
    Path entity = dir.resolve("bad.xml");
    Files.writeString(entity, "<unclosed", StandardCharsets.UTF_8);
    String uri = entity.toUri().toString();
    String text =
        "<!DOCTYPE a SYSTEM \""
            + dtd.toUri()
            + "\" [<!ENTITY e SYSTEM \""
            + uri
            + "\"><!ENTITY % p SYSTEM \""
            + uri
            + "\"> %p;]><a><b>&e;</b></a>";
    assertNull(result(Language.XML, Mode.CHECK, text, TWO).error());
    assertTrue(pretty(text).endsWith("]>\n<a>\n  <b>&e;</b>\n</a>"));
  }

  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void aRemoteDtdIsNotFetched() {
    // 192.0.2.1 is TEST-NET-1: a fetch would hang or fail, and external access is empty.
    String text = "<!DOCTYPE a SYSTEM \"http://192.0.2.1/a.dtd\"><a><b/></a>";
    assertNull(result(Language.XML, Mode.CHECK, text, TWO).error());
  }

  @Test
  void anEntityExpansionBombIsRefused() {
    StringBuilder text = new StringBuilder("<!DOCTYPE l [<!ENTITY l0 'ha'>");
    for (int i = 1; i < 10; i++) {
      text.append("<!ENTITY l")
          .append(i)
          .append(" '")
          .append(("&l" + (i - 1) + ";").repeat(10))
          .append("'>");
    }
    text.append("]><l>&l9;</l>");
    FormatError e = error(Language.XML, Mode.CHECK, text.toString(), TWO);
    assertTrue(e.reason().contains("entity expansions"), e.reason());
  }

  @Test
  void aLargeTextFormatsAndMinifiesBack() {
    StringBuilder flat = new StringBuilder("<r>");
    int items = 200_000;
    for (int i = 0; i < items; i++) {
      flat.append("<item id=\"").append(i).append("\"><v>value ").append(i).append("</v></item>");
    }
    flat.append("</r>");
    assertTrue(flat.length() > 4 * 1024 * 1024);
    String laid = pretty(flat.toString());
    assertTrue(laid.endsWith("    <v>value " + (items - 1) + "</v>\n  </item>\n</r>"));
    assertEquals(flat.toString(), minify(laid));
  }

  @Test
  void aDeepTextFormats() {
    int depth = 20_000;
    String flat = "<a>".repeat(depth) + "</a>".repeat(depth);
    assertNull(result(Language.XML, Mode.MINIFY, flat, TWO).batch());
    assertNull(result(Language.XML, Mode.MINIFY, flat, TWO).error());
  }

  @Test
  void anOutputOverTheLimitFails() {
    FormatLayout small = new FormatLayout("  ", "\n", false, 20);
    FormatError e =
        result(Language.XML, Mode.PRETTY_PRINT, "<a><b/><c/><d/><e/></a>", small).error();
    assertTrue(e.reason().contains("20"), e.reason());
  }

  @Test
  void aCanceledRunThrows() {
    final CancellationToken token = new CancellationToken();
    token.cancel();
    final Document document = FormatFixture.document("<a><b/></a>");
    assertThrows(
        CancellationException.class,
        new Executable() {
          @Override
          public void execute() {
            Formats.run(
                Language.XML,
                Mode.PRETTY_PRINT,
                document.text(),
                SelectionSet.caret(0),
                TWO,
                token);
          }
        });
  }

  @Test
  void aRunCanceledWhileItReadsThrows() {
    StringBuilder flat = new StringBuilder("<r>");
    for (int i = 0; i < 100_000; i++) {
      flat.append("<i/>");
    }
    flat.append("</r>");
    final String text = flat.toString();
    final CancellationToken token = new CancellationToken();
    final int[] reads = new int[1];
    // The parser reads the text in many reads; the third cancels the run.
    final Runnable onRead =
        new Runnable() {
          @Override
          public void run() {
            if (++reads[0] == 3) {
              token.cancel();
            }
          }
        };
    assertThrows(
        CancellationException.class,
        new Executable() {
          @Override
          public void execute() {
            XmlCheck.check(text, token, onRead, false);
          }
        });
    assertEquals(3, reads[0]);
  }
}
