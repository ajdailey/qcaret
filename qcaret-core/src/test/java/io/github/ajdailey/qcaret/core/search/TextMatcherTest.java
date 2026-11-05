package io.github.ajdailey.qcaret.core.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.document.Replacement;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.regex.PatternSyntaxException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

class TextMatcherTest {
  private static final CancellationToken LIVE = new CancellationToken();

  private static TextMatcher literal(String text, boolean caseSensitive, boolean wholeWord) {
    return new SearchQuery(text, false, caseSensitive, wholeWord).compile();
  }

  private static TextMatcher regex(String text) {
    return new SearchQuery(text, true, true, false).compile();
  }

  @Test
  void literalFindsForwardAndBackward() {
    TextMatcher m = literal("a.b", true, false);
    String text = "a.b axb a.b";
    assertEquals(new Match(0, 3), m.next(text, 0, LIVE));
    assertEquals(new Match(8, 11), m.next(text, 1, LIVE));
    assertNull(m.next(text, 9, LIVE));
    assertEquals(new Match(0, 3), m.previous(text, 8, LIVE));
    assertEquals(new Match(8, 11), m.previous(text, 11, LIVE));
    assertNull(m.previous(text, 0, LIVE));
  }

  @Test
  void caseFoldsUnicodeWhenInsensitive() {
    assertEquals(new Match(4, 11), literal("STRASSE", false, false).next("die strasse", 0, LIVE));
    assertEquals(new Match(0, 1), literal("é", false, false).next("É", 0, LIVE));
    assertNull(literal("Abc", true, false).next("abc", 0, LIVE));
  }

  @Test
  void wholeWordRejectsLettersDigitsAndUnderscoreAtEitherEnd() {
    TextMatcher m = literal("id", true, true);
    String text = "_id id2 éid id.";
    assertEquals(List.of(new Match(12, 14)), m.all(text, 0, text.length(), 10, LIVE));
  }

  private static boolean wordMatches(String pattern, String text) {
    return new SearchQuery(pattern, true, true, true).compile().next(text, 0, LIVE) != null;
  }

  /** An open {@code \Q} or a trailing {@code (?x)} comment would swallow the wrapper's group. */
  @Test
  void wholeWordClosesAnOpenQuoteOrComment() {
    String[] patterns = {
      "\\Qa.b",
      "(?x) a \\. b  # the dot is literal",
      "(?x) a\\.b # \\Q quoted in the comment",
      "a\\.b"
    };
    for (String pattern : patterns) {
      assertTrue(wordMatches(pattern, "x a.b y"), pattern);
      assertTrue(wordMatches(pattern, "a.b"), pattern);
      assertFalse(wordMatches(pattern, "xa.b"), pattern);
      assertFalse(wordMatches(pattern, "a.bc"), pattern);
      assertFalse(wordMatches(pattern, "axb"), pattern);
    }
    String word = "[\\p{L}\\p{N}_]";
    assertEquals("(?<!" + word + ")(?:a|ab)(?!" + word + ")", SearchQuery.wholeWord("a|ab"));
    assertTrue(wordMatches("a|ab", "ab"));
  }

  @Test
  void regexAnchorsMatchAtEveryLineBreakKind() {
    TextMatcher m = regex("^x$");
    String text = "x\r\nx\nx\rx";
    assertEquals(4, m.all(text, 0, text.length(), 10, LIVE).size());
  }

  @Test
  void emptyMatchesAreSkipped() {
    TextMatcher m = regex("a*");
    assertEquals(new Match(2, 4), m.next("bbaab", 0, LIVE));
    assertEquals(List.of(new Match(2, 4)), m.all("bbaab", 0, 5, 10, LIVE));
    assertNull(regex("^").next("abc", 0, LIVE));
  }

  @Test
  void lookbehindSeesTextBeforeTheRange() {
    TextMatcher m = regex("(?<=x)y");
    assertEquals(new Match(1, 2), m.next("xy", 1, LIVE));
    assertEquals(List.of(new Match(1, 2)), m.all("xyxy", 1, 2, 10, LIVE));
  }

  @Test
  void allStopsAtTheLimitAndTheRangeEnd() {
    TextMatcher m = literal("a", true, false);
    assertEquals(2, m.all("aaaa", 0, 4, 2, LIVE).size());
    assertEquals(3, m.all("aaaa", 1, 4, 10, LIVE).size());
    assertEquals(1, m.all("aaaa", 0, 1, 10, LIVE).size());
  }

  @Test
  void invalidRegexThrowsAtCompile() {
    assertThrows(
        PatternSyntaxException.class,
        new Executable() {
          @Override
          public void execute() {
            regex("(a");
          }
        });
    assertThrows(
        IllegalArgumentException.class,
        new Executable() {
          @Override
          public void execute() {
            regex("");
          }
        });
  }

  @Test
  void canceledSearchStops() {
    final CancellationToken token = new CancellationToken();
    token.cancel();
    final String text = "b".repeat(3 * TextMatcher.CHECK_CHARS);
    assertThrows(
        CancellationException.class,
        new Executable() {
          @Override
          public void execute() {
            literal("a", true, false).next(text, 0, token);
          }
        });
  }

  @Test
  void stackOverflowBecomesATypedFailure() {
    final String text = "ab".repeat(200_000);
    PatternTooComplexException error =
        assertThrows(
            PatternTooComplexException.class,
            new Executable() {
              @Override
              public void execute() {
                regex("(a|b)*c").next(text, 0, LIVE);
              }
            });
    assertEquals(0, error.offset());
  }

  @Test
  void replacementExpandsGroupsAndEscapes() {
    TextMatcher m = regex("(?<key>\\w+)=(\\w+)");
    String text = "a=1 b=2";
    ReplaceTemplate t = m.template("$2:${key}\\t\\$\\\\\\n$20");
    assertEquals(
        List.of(new Replacement(0, 3, "1:a\t$\\\n10"), new Replacement(4, 3, "2:b\t$\\\n20")),
        m.replaceAll(text, t, LIVE));
    assertEquals("1:a\t$\\\n10", m.replacementAt(text, 0, 3, t, LIVE));
    assertNull(m.replacementAt(text, 0, 2, t, LIVE));
  }

  @Test
  void literalReplacementIsTakenAsIs() {
    TextMatcher m = literal("x", true, false);
    assertEquals(
        List.of(new Replacement(1, 1, "$1\\n")), m.replaceAll("axb", m.template("$1\\n"), LIVE));
  }

  @Test
  void malformedReplacementIsRefused() {
    final TextMatcher m = regex("(a)");
    for (final String bad : new String[] {"x\\", "$", "$2", "${none}", "${open"}) {
      assertThrows(
          IllegalArgumentException.class,
          new Executable() {
            @Override
            public void execute() {
              m.template(bad);
            }
          },
          bad);
    }
  }
}
