package io.github.ajdailey.qcaret.core.syntax;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.document.Document;
import io.github.ajdailey.qcaret.core.document.UndoLimits;
import io.github.ajdailey.qcaret.core.text.LineEnding;
import io.github.ajdailey.qcaret.core.text.TextEncoding;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/** Symbol scans: tokenizer state, rule order, bounds and cancellation. */
class SymbolScannerTest {
  private static final String MOCK =
      "<grammar id=\"mock\" name=\"Mock\"><symbols>"
          + "<symbol regex=\"\\bdef\\s+(\\w+)\" kind=\"function\"/>"
          + "<symbol regex=\"\\bclass\\s+(?&lt;name&gt;\\w+)\" kind=\"class\"/>"
          + "<symbol regex=\"\\b(?:def|var)\\s+(\\w+)\" kind=\"variable\"/>"
          + "</symbols><context id=\"main\">"
          + "<span token=\"comment\" begin=\"/\\*\" end=\"\\*/\" multiLine=\"true\"/>"
          + "<span token=\"comment\" begin=\"#\"/>"
          + "<span token=\"string\" begin=\"&quot;\" end=\"&quot;\" multiLine=\"true\"/>"
          + "</context></grammar>";

  private static Document document(String text) {
    return Document.of(
        text, TextEncoding.of(StandardCharsets.UTF_8, false), LineEnding.LF, UndoLimits.DEFAULTS);
  }

  /** Scans and returns {@code kind:name@line:column} entries. */
  static List<String> scan(Syntax syntax, String text) {
    SymbolScanner.Result result =
        new SymbolScanner(syntax, SyntaxEngine.DEFAULT_MAX_LINE_CHARS, SymbolScanner.MAX_SYMBOLS)
            .scan(document(text).text(), new CancellationToken());
    return describe(result);
  }

  static List<String> describe(SymbolScanner.Result result) {
    List<String> out = new ArrayList<String>();
    for (SymbolScanner.Symbol s : result.symbols()) {
      out.add(s.kind().label() + ":" + s.name() + "@" + s.line() + ":" + s.column());
    }
    return out;
  }

  @Test
  void aDefinitionInsideAStringOrACommentIsIgnored() throws Exception {
    Syntax syntax = Tokens.compile(MOCK);
    assertEquals(
        List.of("function:real@4:7", "class:Kept@5:6"),
        scan(
            syntax,
            "# def inComment\n\"def inString\n def stillString\"\n/* class Hidden\n*/ def real"
                + "\nclass Kept"));
  }

  @Test
  void theFirstRuleWinsAColumnAndALineListsByColumn() throws Exception {
    Syntax syntax = Tokens.compile(MOCK);
    assertEquals(
        List.of("class:A@0:6", "function:f@0:14", "variable:v@0:21"),
        scan(syntax, "class A { def f; var v }"));
  }

  @Test
  void crLfAndCrBreaksCountAsOneLineEach() throws Exception {
    Syntax syntax = Tokens.compile(MOCK);
    assertEquals(
        List.of("function:a@0:4", "function:b@1:4", "function:c@3:4"),
        scan(syntax, "def a\r\ndef b\r\rdef c"));
  }

  @Test
  void aGrammarWithoutRulesFindsNothing() {
    assertEquals(List.of(), scan(Tokens.BUILT_IN.syntax("json"), "{\"def\": 1}"));
  }

  @Test
  void aLargeFileStopsAtTheCountAndSkipsOverLongLines() throws Exception {
    Syntax syntax = Tokens.compile(MOCK);
    StringBuilder text = new StringBuilder();
    text.append("def ").append("x".repeat(300)).append('\n');
    text.append("\"").append("y".repeat(50)).append('\n');
    text.append("def hidden\" def after\n");
    for (int i = 0; i < 5_000; i++) {
      text.append("def f").append(i).append('\n');
    }
    SymbolScanner.Result small =
        new SymbolScanner(syntax, 100, 1_000)
            .scan(document(text.toString()).text(), new CancellationToken());
    assertTrue(small.truncated());
    assertEquals(1_000, small.symbols().size());
    assertEquals(1, small.skippedLines());
    assertEquals("after", small.symbols().get(0).name());
    assertEquals("f998", small.symbols().get(999).name());
    SymbolScanner.Result all =
        new SymbolScanner(syntax, 1_000, SymbolScanner.MAX_SYMBOLS)
            .scan(document(text.toString()).text(), new CancellationToken());
    assertFalse(all.truncated());
    assertEquals(5_002, all.symbols().size());
    assertEquals(SymbolScanner.MAX_NAME_CHARS, all.symbols().get(0).name().length());
    assertEquals(5_002, all.symbols().get(5_001).line());
  }

  @Test
  void aLineOverTheBlockSizeIsSkippedWithoutBreakingTheLinesAfterIt() throws Exception {
    Syntax syntax = Tokens.compile(MOCK);
    String text = "def a\n" + "z".repeat((1 << 20) + 10) + "\ndef b\n";
    SymbolScanner.Result result =
        new SymbolScanner(syntax, SyntaxEngine.DEFAULT_MAX_LINE_CHARS, SymbolScanner.MAX_SYMBOLS)
            .scan(document(text).text(), new CancellationToken());
    assertEquals(List.of("function:a@0:4", "function:b@2:4"), describe(result));
    assertEquals(1, result.skippedLines());
  }

  @Test
  void aCanceledScanThrows() throws Exception {
    final Syntax syntax = Tokens.compile(MOCK);
    final CancellationToken token = new CancellationToken();
    token.cancel();
    assertThrows(
        CancellationException.class,
        new Executable() {
          @Override
          public void execute() {
            new SymbolScanner(syntax, 1_000, 10).scan(document("def a").text(), token);
          }
        });
  }
}
