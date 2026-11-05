package io.github.ajdailey.qcaret.app.editor;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.theme.TokenRole;
import io.github.ajdailey.qcaret.core.document.Document;
import io.github.ajdailey.qcaret.core.document.UndoLimits;
import io.github.ajdailey.qcaret.core.text.LineEnding;
import io.github.ajdailey.qcaret.core.text.TextEncoding;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** Indent rules from grammar regexes and the token filter of bracket matching. */
class GrammarRulesTest {
  private static final int STRING = TokenRole.STRING.ordinal();
  private static final int COMMENT = TokenRole.COMMENT.ordinal();

  @Test
  void patternRulesFindTheirRegexAndReadABoundedPart() {
    PatternIndentRules rules =
        new PatternIndentRules(Pattern.compile(":\\s*(#.*)?$"), Pattern.compile("^\\s*end\\b"));
    assertTrue(rules.increase("if x:  # note"));
    assertFalse(rules.increase("x = 1"));
    assertTrue(rules.decrease("  end"));
    assertFalse(rules.decrease("  ending"));
    // Only the last chars before the caret are read, so a match far back does not count.
    assertTrue(rules.increase("x".repeat(5000) + ":"));
    assertFalse(rules.decrease("x".repeat(PatternIndentRules.MAX_CHARS) + " end"));
    PatternIndentRules none = new PatternIndentRules(null, null);
    assertFalse(none.increase("{"));
    assertFalse(none.decrease("}"));
    // A backtracking regex that reaches the read bound reports false.
    PatternIndentRules slow = new PatternIndentRules(Pattern.compile("(a|aa)+$"), null);
    assertFalse(slow.increase("a".repeat(900) + "b"));
  }

  @Test
  void bracketsInStringAndCommentTokensDoNotCount() {
    Document document =
        Document.of(
            "f(\")\") // (\nx(\n",
            TextEncoding.of(StandardCharsets.UTF_8, false),
            LineEnding.LF,
            UndoLimits.DEFAULTS);
    TokenSource tokens =
        new TokenSource() {
          @Override
          public int[] tokens(int line) {
            return line == 0 ? new int[] {2, 5, STRING, 7, 11, COMMENT} : null;
          }
        };
    TokenBracketFilter filter = new TokenBracketFilter(document.text(), tokens);
    assertTrue(filter.counts(1));
    assertFalse(filter.counts(3));
    assertTrue(filter.counts(5));
    assertFalse(filter.counts(10));
    // Line 1 has no tokens yet: every char counts.
    assertTrue(filter.counts(13));
  }
}
