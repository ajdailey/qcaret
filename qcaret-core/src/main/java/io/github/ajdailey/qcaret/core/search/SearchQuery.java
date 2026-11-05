package io.github.ajdailey.qcaret.core.search;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * What a find searches for.
 *
 * @param text the literal text or the regex; not null
 * @param regex true when {@code text} is a {@link Pattern} regex
 * @param caseSensitive false to fold case, Unicode-aware
 * @param wholeWord true to match only where no letter, digit or {@code _} touches either end
 */
public record SearchQuery(String text, boolean regex, boolean caseSensitive, boolean wholeWord) {
  /** Word chars for whole-word matching. */
  private static final String WORD = "[\\p{L}\\p{N}_]";

  /** Checks the components. */
  public SearchQuery {
    if (text == null) {
      throw new IllegalArgumentException("null text");
    }
  }

  /**
   * Compiles the query. A regex compiles with {@code MULTILINE}, so {@code ^} and {@code $} match
   * at every line break (CR, LF or CR LF), and {@code .} stops at a line break.
   *
   * @return the matcher
   * @throws PatternSyntaxException for an invalid regex
   * @throws IllegalArgumentException for empty text
   */
  public TextMatcher compile() {
    if (text.isEmpty()) {
      throw new IllegalArgumentException("empty query");
    }
    int flags = Pattern.MULTILINE;
    if (!caseSensitive) {
      flags |= Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
    }
    String source = regex ? text : Pattern.quote(text);
    if (wholeWord) {
      source = "(?<!" + WORD + ")(?:" + source + ")(?!" + WORD + ")";
    }
    return new TextMatcher(Pattern.compile(source, flags), regex);
  }
}
