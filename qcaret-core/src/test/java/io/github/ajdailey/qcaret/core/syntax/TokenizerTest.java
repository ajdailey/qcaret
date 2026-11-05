package io.github.ajdailey.qcaret.core.syntax;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import java.util.List;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/** States across lines, the line limit and the bounds on runaway regexes. */
class TokenizerTest {
  private static final String RUNAWAY =
      "<grammar id=\"mock\" name=\"Mock\"><context id=\"main\">"
          + "<match token=\"number\" regex=\"\\d+\"/><match token=\"keyword\" regex=\"(.*a){12}$\"/>"
          + "</context></grammar>";

  @Test
  void aMultiLineSpanCarriesItsStateAndASingleLineSpanEndsAtTheBreak() {
    Syntax java = Tokens.BUILT_IN.syntax("java");
    Tokenizer t = java.tokenizer(100);
    TokenList list = new TokenList();
    CancellationToken token = new CancellationToken();
    int inComment = t.tokenize("int a; /* open", Tokenizer.INITIAL, list, token);
    assertTrue(inComment != Tokenizer.INITIAL);
    assertEquals(2, t.depth(inComment));
    int same = t.tokenize("still inside", inComment, list, token);
    assertEquals(inComment, same);
    assertEquals(TokenType.COMMENT, list.typeAt(0));
    assertEquals(Tokenizer.INITIAL, t.tokenize("end */ \"unclosed", same, list, token));
    assertEquals(TokenType.STRING, list.typeAt(8));
  }

  @Test
  void aLineOverTheLimitHasNoTokensAndKeepsItsState() {
    Tokenizer t = Tokens.BUILT_IN.syntax("java").tokenizer(10);
    TokenList list = new TokenList();
    int state = t.tokenize("/* open", Tokenizer.INITIAL, list, new CancellationToken());
    assertEquals(state, t.tokenize("int x = 1; */ int y;", state, list, new CancellationToken()));
    assertEquals(0, list.count());
    assertFalse(t.failed());
  }

  @Test
  void aRunawayRegexLeavesTheRestOfTheLinePlainAndEndsQuickly() throws Exception {
    Syntax syntax = Tokens.compile(RUNAWAY);
    Tokenizer t = syntax.tokenizer(SyntaxEngine.DEFAULT_MAX_LINE_CHARS);
    TokenList list = new TokenList();
    long started = System.nanoTime();
    t.tokenize("1 " + "a".repeat(32) + "!", Tokenizer.INITIAL, list, new CancellationToken());
    assertTrue(System.nanoTime() - started < 5_000_000_000L);
    assertTrue(t.failed());
    assertEquals(TokenType.NUMBER, list.typeAt(0));
    assertEquals(1, list.count());
  }

  @Test
  void aStackOverflowInTheRegexEngineLeavesTheLinePlain() throws Exception {
    Syntax syntax =
        Tokens.compile(
            "<grammar id=\"mock\" name=\"Mock\"><context id=\"main\">"
                + "<match token=\"string\" regex=\"(?:a|b)*c\"/></context></grammar>");
    Tokenizer t = syntax.tokenizer(Integer.MAX_VALUE);
    TokenList list = new TokenList();
    t.tokenize("ab".repeat(200_000) + "c", Tokenizer.INITIAL, list, new CancellationToken());
    assertTrue(t.failed());
  }

  @Test
  void aCanceledTokenStopsARunawayRegex() throws Exception {
    Syntax syntax = Tokens.compile(RUNAWAY);
    final Tokenizer t = syntax.tokenizer(SyntaxEngine.DEFAULT_MAX_LINE_CHARS);
    final CancellationToken token = new CancellationToken();
    token.cancel();
    assertThrows(
        CancellationException.class,
        new Executable() {
          @Override
          public void execute() {
            t.tokenize("a".repeat(32) + "!", Tokenizer.INITIAL, new TokenList(), token);
          }
        });
  }

  @Test
  void zeroWidthRulesThatOnlySwitchContextsStillAdvance() throws Exception {
    Syntax syntax =
        Tokens.compile(
            "<grammar id=\"mock\" name=\"Mock\"><context id=\"main\">"
                + "<match regex=\"(?=x)\" push=\"other\"/></context>"
                + "<context id=\"other\"><match regex=\"(?=x)\" pop=\"true\"/></context></grammar>");
    List<String> tokens = Tokens.of(syntax, "xxx");
    assertEquals(List.of(), tokens);
  }

  @Test
  void backreferencesAndNamedGroupsAreRejected() {
    for (final String regex : new String[] {"(a)\\1", "(?&lt;n&gt;a)\\k&lt;n&gt;"}) {
      assertThrows(
          InvalidGrammarException.class,
          new Executable() {
            @Override
            public void execute() throws Exception {
              Tokens.compile(
                  "<grammar id=\"mock\" name=\"Mock\"><context id=\"main\">"
                      + "<match token=\"string\" regex=\""
                      + regex
                      + "\"/></context></grammar>");
            }
          });
    }
  }

  @Test
  void anEscapedBackslashIsNotABackreference() throws Exception {
    Syntax syntax =
        Tokens.compile(
            "<grammar id=\"mock\" name=\"Mock\"><context id=\"main\">"
                + "<match token=\"escape\" regex=\"\\\\1\"/></context></grammar>");
    assertEquals(List.of("ESCAPE:\\1"), Tokens.of(syntax, "\\1"));
  }

  private static Syntax spans(String spans) throws Exception {
    return Tokens.compile(
        "<grammar id=\"mock\" name=\"Mock\"><context id=\"main\">"
            + spans
            + "<match token=\"keyword\" regex=\"x\"/></context></grammar>");
  }

  @Test
  void aNestedSpanClosesAtTheDepthItOpened() throws Exception {
    Syntax syntax =
        spans(
            "<span token=\"comment\" begin=\"\\{-\" end=\"-\\}\" multiLine=\"true\""
                + " nested=\"true\"/>");
    assertEquals(
        List.of("COMMENT:{- a {- b", "COMMENT:-} c -}", "KEYWORD:x"),
        Tokens.of(syntax, "{- a {- b\n-} c -} x"));
    Tokenizer t = syntax.tokenizer(100);
    int state = t.tokenize("{- {- {-", Tokenizer.INITIAL, new TokenList(), new CancellationToken());
    assertEquals(4, t.depth(state));
  }

  @Test
  void anEndUsingGroupOneClosesOnlyOnTheOpenersText() throws Exception {
    Syntax syntax =
        spans(
            "<span token=\"string\" begin=\"\\[(=*)\\[\" end=\"\\]\\1\\]\" multiLine=\"true\"/>"
                + "<span token=\"string\" begin=\"&lt;&lt;([A-Z]+)\" end=\"^\\1$\""
                + " multiLine=\"true\"/>");
    assertEquals(
        List.of("STRING:[==[ ]] ]=]", "STRING:x ]==]", "KEYWORD:x"),
        Tokens.of(syntax, "[==[ ]] ]=]\nx ]==] x"));
    assertEquals(List.of("STRING:[[ x ]]", "KEYWORD:x"), Tokens.of(syntax, "[[ x ]] x"));
    assertEquals(
        List.of("STRING:<<EOT", "STRING: EOT", "STRING:EOTX", "STRING:EOT", "KEYWORD:x"),
        Tokens.of(syntax, "<<EOT\n EOT\nEOTX\nEOT\nx"));
  }

  @Test
  void anEscapeUsingGroupOneMatchesTheOpenersText() throws Exception {
    Syntax syntax =
        spans(
            "<span token=\"string\" begin=\"(#+)&quot;\" end=\"&quot;\\1\""
                + " escape=\"\\\\\\1n\"/>");
    assertEquals(
        List.of("STRING:##\"a \\n \"# ", "ESCAPE:\\##n", "STRING:\"##", "KEYWORD:x"),
        Tokens.of(syntax, "##\"a \\n \"# \\##n\"## x"));
  }

  @Test
  void equalOpenersShareOneDerivedContext() throws Exception {
    Syntax syntax =
        spans("<span token=\"string\" begin=\"&lt;(\\w*)\" end=\"\\1&gt;\" multiLine=\"true\"/>");
    Tokenizer t = syntax.tokenizer(100);
    TokenList list = new TokenList();
    CancellationToken token = new CancellationToken();
    int a = t.tokenize("<ab", Tokenizer.INITIAL, list, token);
    assertEquals(a, t.tokenize("<ab", Tokenizer.INITIAL, list, token));
    assertTrue(a != t.tokenize("<abc", Tokenizer.INITIAL, list, token));
  }

  @Test
  void anOpenerPastTheLimitsColorsItselfAndOpensNothing() throws Exception {
    Syntax syntax =
        spans("<span token=\"string\" begin=\"&lt;(\\w*)\" end=\"\\1&gt;\" multiLine=\"true\"/>");
    String longText = "a".repeat(Syntax.MAX_CAPTURE + 1);
    assertEquals(
        List.of("STRING:<" + longText, "KEYWORD:x"), Tokens.of(syntax, "<" + longText + " x"));
    Tokenizer t = syntax.tokenizer(1000);
    TokenList list = new TokenList();
    CancellationToken token = new CancellationToken();
    for (int i = 0; i < Syntax.MAX_DERIVED; i++) {
      assertEquals(2, t.depth(t.tokenize("<w" + i, Tokenizer.INITIAL, list, token)));
    }
    assertEquals(Tokenizer.INITIAL, t.tokenize("<over x", Tokenizer.INITIAL, list, token));
    assertEquals(TokenType.KEYWORD, list.typeAt(6));
  }

  @Test
  void theStackStopsGrowingAtItsLimit() throws Exception {
    Syntax syntax =
        spans(
            "<span token=\"comment\" begin=\"\\(\\*\" end=\"\\*\\)\" multiLine=\"true\""
                + " nested=\"true\"/>");
    Tokenizer t = syntax.tokenizer(Integer.MAX_VALUE);
    int state =
        t.tokenize(
            "(*".repeat(Tokenizer.MAX_DEPTH * 2),
            Tokenizer.INITIAL,
            new TokenList(),
            new CancellationToken());
    assertEquals(Tokenizer.MAX_DEPTH, t.depth(state));
  }

  @Test
  void groupOneNeedsAGroupInTheBeginAndOtherBackreferencesStayRefused() {
    for (final String span :
        new String[] {
          "<span token=\"string\" begin=\"a\" end=\"\\1\"/>",
          "<span token=\"string\" begin=\"(a)(b)\" end=\"\\2\"/>"
        }) {
      assertThrows(
          InvalidGrammarException.class,
          new Executable() {
            @Override
            public void execute() throws Exception {
              spans(span);
            }
          });
    }
  }
}
