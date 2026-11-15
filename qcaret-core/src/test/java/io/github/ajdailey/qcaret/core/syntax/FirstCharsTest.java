package io.github.ajdailey.qcaret.core.syntax;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** The first-char filter: a position it skips is one where the rule cannot match. */
class FirstCharsTest {
  private static final String[] PIECES = {
    "foo",
    "x1",
    "_a",
    "Mock",
    "if",
    "else",
    "def",
    "function",
    "class",
    "return",
    "typeof",
    "when",
    "do",
    "case",
    "true",
    "null",
    "ERROR",
    "WARN",
    "INFO",
    "E",
    "W",
    "I",
    "D",
    "T",
    "42",
    "0x1F",
    "3.14e-2",
    "1_000",
    "2026-11-14",
    "2026-11-14T12:00:00Z",
    "12:00:00",
    "2026/11/14 12:00:00",
    "Nov 14 12:00:00",
    "[14/Nov/2026:12:00:00 +0000]",
    "[E]",
    "<W>",
    "E/Tag:",
    "E1114 12:00",
    "//",
    "/*",
    "*/",
    "#",
    "--",
    "<!--",
    "-->",
    "\"",
    "'",
    "`",
    "$",
    "${",
    "$((",
    "((",
    "@",
    "<",
    ">",
    "</",
    "{",
    "}",
    "(",
    ")",
    "[",
    "]",
    "\\",
    ":",
    "::",
    ";",
    "=",
    "==",
    ".",
    ",",
    "|",
    "&",
    "%",
    "%%",
    "^",
    "~",
    "?",
    "!",
    "*",
    "**",
    "_",
    "+",
    "-",
    "/",
    "/x/",
    "/[a]/g",
    "<<EOF",
    "[[",
    "]]",
    "\"a\"",
    "'b'",
    "http://example.invalid/a",
    "<mailto:x>",
    ":sym",
    "key:",
    "a: b",
    "  - c: d",
    "\t",
    " ",
    "  "
  };

  @Test
  void aLookbehindOnEveryTopLevelAlternativeExcludesAfterAWord() {
    assertFalse(FirstChars.afterWord("(?<!\\w)a|(?<![\\w.])b"));
    assertFalse(FirstChars.afterWord("(?i)(?<!\\w)a|(?=b)(?<!\\w)b"));
    assertFalse(FirstChars.afterWord("(?<!\\w)(?:a|b)"));
    assertFalse(FirstChars.afterWord("(?<!\\w)a[|]b"));
  }

  @Test
  void anAlternativeWithoutTheLookbehindMayStartAfterAWord() {
    assertTrue(FirstChars.afterWord("(?<!\\w)a|b"));
    assertTrue(FirstChars.afterWord("a|(?<!\\w)b"));
    assertTrue(FirstChars.afterWord("(?<!\\w)a|(?<!x)b"));
    assertTrue(FirstChars.afterWord("(?<!\\w)?a"));
    assertTrue(FirstChars.afterWord("(?<!\\w)a\\Q|\\Eb"));
  }

  @Test
  void anAssertionAfterTheStartDoesNotNarrowTheFirstChars() {
    assertTrue(starts("foo|(?<=\\.)bar", 'b'));
    assertTrue(starts("foo|\\Bbar", 'b'));
    assertTrue(starts("x|\\b\\.", '.'));
    assertTrue(starts("(?:a|(?<=a)b)", 'b'));
    assertTrue(starts("\\Qa(?<=b)\\E", 'a'));
    assertFalse(starts("\\bfoo\\b|(?<!\\w)bar", 'x'));
    assertTrue(starts("(?!a\\b)a", 'a'));
    assertTrue(starts("@(?!interface\\b)\\w+", '@'));
    assertFalse(starts("@(?!interface\\b)\\w+", 'i'));
  }

  @Test
  void anAssertionWhoseRemovalMayNarrowLeavesTheRuleUnprobed() {
    assertNull(FirstChars.probe("(?>(?!\\bx)y)"));
    assertNull(FirstChars.probe("(?>\\ba|ab)c"));
    assertNull(FirstChars.probe("(\\ba|ab)++c"));
    assertNull(FirstChars.probe("a(?<=a){2}"));
  }

  @Test
  void aCommonLogTimestampAfterAWordIsANumber() {
    List<String> tokens = Tokens.of("log", "x[14/Nov/2026:12:00:00 +0000]");
    assertTrue(tokens.contains("NUMBER:[14/Nov/2026:12:00:00 +0000]"), tokens.toString());
  }

  @Test
  void everyBuiltInRuleMatchesOnlyWhereTheFilterTriesIt() {
    List<String> corpus = corpus(new Random(1), 400);
    for (String id : GrammarSet.BUILT_IN) {
      Syntax syntax = Tokens.BUILT_IN.syntax(id);
      for (int c = 0; c < syntax.contextCount(); c++) {
        Syntax.Context context = syntax.context(c);
        List<Syntax.Alt> alts = new ArrayList<Syntax.Alt>(context.alts);
        if (context.embedEnd != null) {
          alts.add(context.embedEnd);
        }
        for (Syntax.Alt alt : alts) {
          check(id + " " + context.name, alt, corpus);
        }
      }
    }
  }

  private static void check(String where, Syntax.Alt alt, List<String> corpus) {
    for (String line : corpus) {
      Matcher m = alt.pattern().matcher(line);
      m.useTransparentBounds(true);
      m.useAnchoringBounds(false);
      for (int at = 0; at <= line.length(); at++) {
        if (!tried(alt, line, at)) {
          m.region(at, line.length());
          if (m.lookingAt()) {
            fail(where + ": " + alt.pattern() + " matches at " + at + " of \"" + line + "\"");
          }
        }
      }
    }
  }

  /** Reports whether the tokenizer's filter tries a rule at a position, as {@code Tokenizer}. */
  private static boolean tried(Syntax.Alt alt, String line, int at) {
    if (at == line.length()) {
      return alt.atEnd();
    }
    char c = line.charAt(at);
    if (!alt.starts()[Math.min(c, Syntax.NON_ASCII)]) {
      return false;
    }
    return at == 0 || !word(line.charAt(at - 1)) || alt.afterWord();
  }

  private static boolean word(char c) {
    return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_';
  }

  private static boolean starts(String regex, char c) {
    Syntax.Alt alt =
        Syntax.Alt.of(Pattern.compile(regex), Syntax.Kind.MATCH, TokenType.NUMBER, -1, false);
    return alt.starts()[c];
  }

  /** Lines of pieces, glued or spaced, with a random printable ASCII or non-ASCII char between. */
  private static List<String> corpus(Random random, int lines) {
    List<String> out = new ArrayList<String>();
    for (int i = 0; i < lines; i++) {
      StringBuilder line = new StringBuilder();
      int pieces = 1 + random.nextInt(8);
      for (int p = 0; p < pieces; p++) {
        int glue = random.nextInt(6);
        if (glue == 0) {
          line.append(' ');
        } else if (glue == 1) {
          line.append((char) (' ' + random.nextInt(95)));
        } else if (glue == 2 && random.nextInt(8) == 0) {
          line.append('é');
        }
        line.append(PIECES[random.nextInt(PIECES.length)]);
      }
      out.add(line.toString());
    }
    return out;
  }
}
