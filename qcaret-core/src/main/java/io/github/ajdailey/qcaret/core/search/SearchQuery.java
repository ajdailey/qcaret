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

  /** Text tried after a pattern so the wrapper's closing group stays outside it. */
  private static final String[] CLOSERS = {"", "\\E", "\n", "\\E\n"};

  /** Characters with a meaning in a {@link Pattern} outside a character class. */
  private static final String REGEX_META = "\\^$.|?*+()[]{}";

  /** Shortest literal a case-sensitive query is prefiltered by. */
  static final int MIN_PREFILTER_CHARS = 3;

  /** Checks the components. */
  public SearchQuery {
    if (text == null) {
      throw new IllegalArgumentException("null text");
    }
  }

  /**
   * Compiles the query. A regex compiles with {@code MULTILINE}, so {@code ^} and {@code $} match
   * at every line break (CR, LF or CR LF), and {@code .} stops at a line break. A case-sensitive
   * query whose matches all start with a literal of at least {@value #MIN_PREFILTER_CHARS} chars
   * (the text, or a regex's {@link #requiredPrefix}) runs the regex only where that literal occurs.
   * Not for a regex holding {@code \G}, which matches where the search starts, or a literal
   * starting with a low surrogate, which {@link Pattern} does not match inside a surrogate pair.
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
      source = wholeWord(source);
    }
    String literal = null;
    if (caseSensitive && !(regex && text.contains("\\G"))) {
      literal = regex ? requiredPrefix(text) : text;
      if (literal.length() < MIN_PREFILTER_CHARS || Character.isLowSurrogate(literal.charAt(0))) {
        literal = null;
      }
    }
    return new TextMatcher(Pattern.compile(source, flags), regex, literal);
  }

  /**
   * Returns the chars before a regex's first metacharacter, less the last code point when a {@code
   * ?}, {@code *} or <code>{</code> quantifier follows it; empty when the pattern has a {@code |}.
   *
   * @param pattern the regex
   * @return a literal every match starts with, possibly empty
   */
  static String requiredPrefix(String pattern) {
    if (pattern.indexOf('|') >= 0) {
      return "";
    }
    int end = 0;
    while (end < pattern.length() && REGEX_META.indexOf(pattern.charAt(end)) < 0) {
      end++;
    }
    if (end > 0 && end < pattern.length() && "?*{".indexOf(pattern.charAt(end)) >= 0) {
      end = pattern.offsetByCodePoints(end, -1);
    }
    return pattern.substring(0, end);
  }

  /**
   * Wraps a regex so a match has no letter, digit or {@code _} touching either end. The pattern is
   * closed first with {@code \E} when it ends inside {@code \Q}, with a line feed when it ends
   * inside a {@code (?x)} comment, or with both for a {@code \Q} inside such a comment, which
   * {@link Pattern} still reads as a quote. Each would otherwise swallow the wrapper's closing
   * parenthesis.
   *
   * @param pattern a regex that compiles alone
   * @return the wrapped regex
   * @throws PatternSyntaxException when the pattern does not compile
   */
  public static String wholeWord(String pattern) {
    int groups = Pattern.compile(pattern).matcher("").groupCount();
    for (String closer : CLOSERS) {
      String closed = pattern + closer;
      try {
        // A group appended after the closer counts only when the closer ends quoting or a comment.
        if (Pattern.compile(closed + "()").matcher("").groupCount() == groups + 1) {
          return "(?<!" + WORD + ")(?:" + closed + ")(?!" + WORD + ")";
        }
      } catch (PatternSyntaxException e) {
        // A stray \E outside \Q; the next closer applies.
      }
    }
    throw new PatternSyntaxException("Whole word cannot close the pattern", pattern, -1);
  }
}
