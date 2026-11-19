package io.github.ajdailey.qcaret.core.format;

import static io.github.ajdailey.qcaret.core.format.FormatFixture.STRICT;
import static io.github.ajdailey.qcaret.core.format.FormatFixture.TWO;
import static io.github.ajdailey.qcaret.core.format.FormatFixture.error;
import static io.github.ajdailey.qcaret.core.format.FormatFixture.format;
import static io.github.ajdailey.qcaret.core.format.FormatFixture.formatSelections;
import static io.github.ajdailey.qcaret.core.format.FormatFixture.result;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.document.Document;
import io.github.ajdailey.qcaret.core.document.Selection;
import io.github.ajdailey.qcaret.core.document.SelectionSet;
import io.github.ajdailey.qcaret.core.format.Formats.Language;
import io.github.ajdailey.qcaret.core.format.Formats.Mode;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/** JSON Pretty Print, Minify and Check through {@link Formats}, lenient and strict. */
class JsonFormatTest {
  private static String pretty(String text) {
    return format(Language.JSON, Mode.PRETTY_PRINT, text, TWO);
  }

  private static String minify(String text) {
    return format(Language.JSON, Mode.MINIFY, text, TWO);
  }

  @Test
  void prettyPrintPutsOneMemberPerLineAndMinifyUndoesIt() {
    String flat = "{\"a\":1,\"b\":[true,null,{}],\"c\":[],\"d\":{\"e\":\"f\"}}";
    String laid =
        "{\n  \"a\": 1,\n  \"b\": [\n    true,\n    null,\n    {}\n  ],\n  \"c\": [],\n"
            + "  \"d\": {\n    \"e\": \"f\"\n  }\n}";
    assertEquals(laid, pretty(flat));
    assertEquals(flat, minify(laid));
    assertEquals(laid, pretty(laid));
    assertEquals("[]", pretty("[ \n ]"));
  }

  @Test
  void numbersEscapesAndScalarsStay() {
    String flat =
        "[1.0E10,-0,12345678901234567890,\"\\u00e9\\n\\\"\\/\\\\\",\"\uD83D\uDE00\",-1.5e-3]";
    assertEquals(flat, minify(pretty(flat)));
    assertTrue(pretty(flat).contains("\n  12345678901234567890,\n"));
    assertEquals(" 7 \n", pretty(" 7 \n"));
    assertEquals("\"s\"", minify("\"s\""));
  }

  @Test
  void edgesStayAndTheDocumentLineEndingIsUsed() {
    FormatLayout crlf = new FormatLayout("\t", "\r\n", false, 1 << 20);
    assertEquals(
        "{\r\n\t\"a\": [\r\n\t\t1\r\n\t]\r\n}\r\n",
        format(Language.JSON, Mode.PRETTY_PRINT, "{\"a\":[1]}\r\n", crlf));
    FormatLayout cr = new FormatLayout("  ", "\r", false, 1 << 20);
    assertEquals("[\r  1\r]", format(Language.JSON, Mode.PRETTY_PRINT, "[\r\r1]", cr));
  }

  @Test
  void aSelectionIndentsFromItsFirstLine() {
    String text = "x = {\n\t\"cfg\": {\"a\":[1,2]}\n}";
    int start = text.indexOf("{\"a\"");
    int end = text.indexOf("]}") + 2;
    assertEquals(
        "x = {\n\t\"cfg\": {\n\t  \"a\": [\n\t    1,\n\t    2\n\t  ]\n\t}\n}",
        formatSelections(Language.JSON, Mode.PRETTY_PRINT, text, TWO, Selection.of(start, end)));
  }

  @Test
  void extensionsAreErrorsAtTheirPosition() {
    assertError("[1,]", 3, "Expected a value");
    assertError("{'a':1}", 1, "Expected a member name or '}'");
    assertError("{a:1}", 1, "Expected a member name or '}'");
    assertError("[NaN]", 1, "Expected a value");
    assertError("[Infinity]", 1, "Expected a value");
    assertError("[-Infinity]", 2, "Invalid number");
    assertError("[01]", 2, "Expected ',' or ']'");
    assertError("[1.]", 3, "Invalid number");
    assertError("[\"a\tb\"]", 3, "Control character in a string");
    assertError("[\"\\x\"]", 2, "Invalid escape");
    assertError("[\"\\u12\"]", 2, "Invalid \\u escape");
    assertError("[\"abc", 1, "Unterminated string");
    assertError("{\"a\" 1}", 5, "Expected ':'");
    assertError("{\"a\":1 \"b\":2}", 7, "Expected ',' or '}'");
    assertError("[1] [2]", 4, "Unexpected text after the JSON value");
    assertError("  ", 2, "Expected a value");
    assertError("[1", 2, "Unexpected end of the JSON text");
  }

  private static void assertError(String text, int offset, String reason) {
    FormatError e = error(Language.JSON, Mode.CHECK, text, TWO);
    assertEquals(reason, e.reason(), text);
    assertEquals(offset, e.offset(), text);
    assertEquals(e, error(Language.JSON, Mode.PRETTY_PRINT, text, TWO));
  }

  @Test
  void anErrorNamesItsLineAndColumn() {
    FormatError e = error(Language.JSON, Mode.CHECK, "{\r\n  \"a\": tru\r\n}", TWO);
    assertEquals(2, e.line());
    assertEquals(8, e.column());
  }

  @Test
  void deepNestingChecksAndMinifiesWithoutRecursion() {
    int depth = 100_000;
    String flat = "[".repeat(depth) + "{\"k\":1}" + "]".repeat(depth);
    assertNull(result(Language.JSON, Mode.CHECK, flat, TWO).error());
    assertEquals(flat, minify(flat));
    String spaced = "[ ".repeat(depth) + "1" + " ]".repeat(depth);
    assertEquals("[".repeat(depth) + "1" + "]".repeat(depth), minify(spaced));
    // Pretty Print of this nesting outgrows any output limit.
    assertTrue(
        result(Language.JSON, Mode.PRETTY_PRINT, flat, TWO).error().reason().contains("exceed"));
  }

  @Test
  void aLargeTextFormatsAndMinifiesBack() {
    StringBuilder flat = new StringBuilder("[");
    int items = 250_000;
    for (int i = 0; i < items; i++) {
      flat.append(i == 0 ? "" : ",").append("{\"id\":").append(i).append(",\"v\":\"x y\"}");
    }
    flat.append("]");
    assertTrue(flat.length() > 4 * 1024 * 1024);
    String laid = pretty(flat.toString());
    assertTrue(laid.endsWith("    \"v\": \"x y\"\n  }\n]"));
    assertEquals(flat.toString(), minify(laid));
  }

  @Test
  void commentsStayWhereTheyStood() {
    String in =
        "// head\n{ // after open\n\"a\" : 1, // after a\n/* own */ \"b\":[ /* in */ 2\n"
            + "// before close\n]} // tail";
    String out =
        "// head\n{ // after open\n  \"a\": 1, // after a\n  /* own */\n  \"b\": [ /* in */\n"
            + "    2\n    // before close\n  ]\n} // tail";
    assertEquals(out, pretty(in));
    assertEquals(out, pretty(out));
    assertTrue(result(Language.JSON, Mode.CHECK, in, TWO).comments());
  }

  @Test
  void aLineCommentIsFollowedByABreak() {
    assertEquals("{\n  \"a\": // why\n  1\n}", pretty("{\"a\": // why\n1}"));
    assertEquals("[\n  1 // one\n  ,\n  2\n]", pretty("[1 // one\n,2]"));
  }

  @Test
  void aLineCommentAtTheEndWithoutABreakChecks() {
    String text = "{\"a\":1} // end";
    assertNull(result(Language.JSON, Mode.CHECK, text, TWO).error());
    assertEquals("{\n  \"a\": 1\n} // end", pretty(text));
  }

  @Test
  void minifyKeepsBlockCommentsAndRefusesLineComments() {
    assertEquals("[1/* one */,2]", minify("[ 1 /* one */ ,\n 2 ]"));
    FormatError e = error(Language.JSON, Mode.MINIFY, "[1,\n  // two\n2]", TWO);
    assertEquals(2, e.line());
    assertEquals(3, e.column());
    assertEquals("Minify cannot keep a // comment", e.reason());
  }

  @Test
  void commentMarksInsideAStringAreText() {
    String text = "{\"a\":\"/* x // y\"}";
    FormatResult result = result(Language.JSON, Mode.CHECK, text, STRICT);
    assertNull(result.error());
    assertFalse(result.comments());
    assertEquals(
        "{\n  \"a\": \"/* x // y\"\n}", format(Language.JSON, Mode.PRETTY_PRINT, text, STRICT));
  }

  @Test
  void strictRefusesACommentAtItsPosition() {
    for (Mode mode : Mode.values()) {
      FormatError e = error(Language.JSON, mode, "[1, /* c */ 2]", STRICT);
      assertEquals(4, e.offset());
      assertEquals("Comments are not allowed in strict JSON", e.reason());
    }
    assertEquals(8, error(Language.JSON, Mode.CHECK, "{\"a\":1} // end", STRICT).offset());
  }

  @Test
  void anUnterminatedOrBareSlashIsAnError() {
    assertEquals("Unterminated comment", error(Language.JSON, Mode.CHECK, "[1 /* x", TWO).reason());
    assertEquals(
        "Unexpected character '/'", error(Language.JSON, Mode.CHECK, "[1 / 2]", TWO).reason());
  }

  @Test
  void aCanceledRunThrows() {
    final CancellationToken token = new CancellationToken();
    token.cancel();
    final Document document = FormatFixture.document("[" + "1,".repeat(10_000) + "1]");
    assertThrows(
        CancellationException.class,
        new Executable() {
          @Override
          public void execute() {
            Formats.run(
                Language.JSON, Mode.CHECK, document.text(), SelectionSet.caret(0), TWO, token);
          }
        });
  }
}
