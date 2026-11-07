package io.github.ajdailey.qcaret.core.search;

import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.WorkerThread;
import io.github.ajdailey.qcaret.core.document.Replacement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.IntConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A compiled {@link SearchQuery}. Every search checks the cancellation token every {@value
 * #CHECK_CHARS} chars it reads, so a catastrophic regex stops on cancel, and a {@link
 * StackOverflowError} in the regex engine becomes a {@link PatternTooComplexException}. Empty
 * matches are skipped. Lookbehind and lookahead see the text outside a searched range. With a
 * prefilter literal, a search runs the regex anchored at each occurrence of the literal, which
 * gives the matches of a {@link Matcher#find} from the search's start. Thread-safe; each call
 * creates its own {@link Matcher}.
 */
public final class TextMatcher {
  /** Chars read between cancellation checks. */
  static final int CHECK_CHARS = 4096;

  private final Pattern pattern;
  private final boolean regex;
  private final String literal;
  private final IntConsumer starts;

  /**
   * Creates a matcher.
   *
   * @param pattern the compiled query
   * @param regex true for a regex query
   * @param literal text every match starts with, or null to run the regex from each search start
   */
  TextMatcher(Pattern pattern, boolean regex, String literal) {
    this(pattern, regex, literal, null);
  }

  private TextMatcher(Pattern pattern, boolean regex, String literal, IntConsumer starts) {
    this.pattern = pattern;
    this.regex = regex;
    this.literal = literal;
    this.starts = starts;
  }

  /** Returns the prefilter literal, or null. */
  String literal() {
    return literal;
  }

  /** Returns a copy without the prefilter literal. */
  TextMatcher withoutLiteral() {
    return new TextMatcher(pattern, regex, null, starts);
  }

  /** Returns a copy that passes each offset the regex runs from to {@code starts}. */
  TextMatcher reportingStarts(IntConsumer starts) {
    return new TextMatcher(pattern, regex, literal, starts);
  }

  /**
   * Parses a replacement text for this query; see {@link ReplaceTemplate}.
   *
   * @param text the replacement
   * @return the template
   * @throws IllegalArgumentException for a malformed regex replacement
   */
  public ReplaceTemplate template(String text) {
    return ReplaceTemplate.parse(text, pattern, regex);
  }

  /**
   * Finds the first match that starts at or after an offset.
   *
   * @param text the text
   * @param from the first offset a match may start at
   * @param token checked while reading
   * @return the match, or null
   * @throws CancellationException when canceled
   * @throws PatternTooComplexException when the regex overflows the stack
   */
  @WorkerThread
  public Match next(CharSequence text, int from, CancellationToken token) {
    Matcher m = matcher(text, token, from);
    try {
      int at = from;
      while (at <= text.length() && find(m, text, at, text.length() + 1, token)) {
        if (m.end() > m.start()) {
          return new Match(m.start(), m.end());
        }
        at = m.end() + 1;
      }
      return null;
    } catch (StackOverflowError e) {
      throw new PatternTooComplexException(from);
    }
  }

  /**
   * Finds the last match that starts before an offset.
   *
   * @param text the text
   * @param before the offset a match must start before
   * @param token checked while reading
   * @return the match, or null
   * @throws CancellationException when canceled
   * @throws PatternTooComplexException when the regex overflows the stack
   */
  @WorkerThread
  public Match previous(CharSequence text, int before, CancellationToken token) {
    Matcher m = matcher(text, token, 0);
    Match last = null;
    try {
      int at = 0;
      while (at <= text.length() && find(m, text, at, before, token) && m.start() < before) {
        if (m.end() > m.start()) {
          last = new Match(m.start(), m.end());
          at = m.start() + 1;
        } else {
          at = m.end() + 1;
        }
      }
      return last;
    } catch (StackOverflowError e) {
      throw new PatternTooComplexException(0);
    }
  }

  /**
   * Lists the matches that start in a range, each after the end of the one before.
   *
   * @param text the text
   * @param from the first offset a match may start at
   * @param to the offset every match must start before
   * @param limit the most matches returned
   * @param token checked while reading
   * @return the matches by offset
   * @throws CancellationException when canceled
   * @throws PatternTooComplexException when the regex overflows the stack
   */
  @WorkerThread
  public List<Match> all(CharSequence text, int from, int to, int limit, CancellationToken token) {
    List<Match> found = new ArrayList<Match>();
    Matcher m = matcher(text, token, from);
    try {
      int at = from;
      while (found.size() < limit && at < to && find(m, text, at, to, token) && m.start() < to) {
        if (m.end() > m.start()) {
          found.add(new Match(m.start(), m.end()));
          at = m.end();
        } else {
          at = m.end() + 1;
        }
      }
      return found;
    } catch (StackOverflowError e) {
      throw new PatternTooComplexException(from);
    }
  }

  /**
   * Returns the replacement for a range when the query matches exactly that range.
   *
   * @param text the text
   * @param start the range's first offset
   * @param end the offset after the range
   * @param template the replacement
   * @param token checked while reading
   * @return the replacement text, or null when no match spans exactly the range
   * @throws CancellationException when canceled
   * @throws PatternTooComplexException when the regex overflows the stack
   */
  @WorkerThread
  public String replacementAt(
      CharSequence text, int start, int end, ReplaceTemplate template, CancellationToken token) {
    if (end <= start) {
      return null;
    }
    Matcher m = matcher(text, token, start);
    try {
      m.region(start, end);
      return m.matches() ? template.expand(m) : null;
    } catch (StackOverflowError e) {
      throw new PatternTooComplexException(start);
    }
  }

  /**
   * Computes the replacement of every match in the text, as for Replace All.
   *
   * @param text the text
   * @param template the replacement
   * @param token checked while reading
   * @return one replacement per match, by offset
   * @throws CancellationException when canceled
   * @throws PatternTooComplexException when the regex overflows the stack
   */
  @WorkerThread
  public List<Replacement> replaceAll(
      CharSequence text, ReplaceTemplate template, CancellationToken token) {
    List<Replacement> out = new ArrayList<Replacement>();
    Matcher m = matcher(text, token, 0);
    try {
      int at = 0;
      while (at <= text.length() && find(m, text, at, text.length() + 1, token)) {
        if (m.end() > m.start()) {
          out.add(new Replacement(m.start(), m.end() - m.start(), template.expand(m)));
          at = m.end();
        } else {
          at = m.end() + 1;
        }
      }
      return out;
    } catch (StackOverflowError e) {
      throw new PatternTooComplexException(0);
    }
  }

  /**
   * Positions {@code m} on the first match starting at or after {@code at}, as {@code m.find(at)}
   * does. With a literal, only matches starting before {@code before} are found.
   *
   * @return false when there is none
   */
  private boolean find(Matcher m, CharSequence text, int at, int before, CancellationToken token) {
    if (literal == null) {
      report(at);
      return m.find(at);
    }
    int from = at;
    while (true) {
      int found = indexOfLiteral(text, from, before, token);
      if (found < 0) {
        return false;
      }
      report(found);
      m.region(found, text.length());
      if (m.lookingAt()) {
        return true;
      }
      from = found + 1;
    }
  }

  private void report(int offset) {
    if (starts != null) {
      starts.accept(offset);
    }
  }

  /**
   * Returns the first offset in {@code [from, before)} where the literal starts, or -1, checking
   * the token every {@value #CHECK_CHARS} chars. A {@link String}, also behind a {@link
   * TextSearch.Prefix}, is searched with {@link String#indexOf(String, int, int)}; other text is
   * read char by char.
   */
  private int indexOfLiteral(CharSequence text, int from, int before, CancellationToken token) {
    int last = Math.min(before, text.length() - literal.length() + 1);
    CharSequence flat = text instanceof TextSearch.Prefix prefix ? prefix.text() : text;
    if (flat instanceof String string) {
      for (int at = from; at < last; at += CHECK_CHARS) {
        token.check();
        int end = (int) Math.min(last, (long) at + CHECK_CHARS);
        int found = string.indexOf(literal, at, end - 1 + literal.length());
        if (found >= 0) {
          return found;
        }
      }
      return -1;
    }
    char first = literal.charAt(0);
    int check = from;
    for (int at = from; at < last; at++) {
      if (at >= check) {
        token.check();
        check = at + CHECK_CHARS;
      }
      if (text.charAt(at) == first && startsWith(text, at)) {
        return at;
      }
    }
    return -1;
  }

  private boolean startsWith(CharSequence text, int at) {
    for (int i = 1; i < literal.length(); i++) {
      if (text.charAt(at + i) != literal.charAt(i)) {
        return false;
      }
    }
    return true;
  }

  private Matcher matcher(CharSequence text, CancellationToken token, int from) {
    if (from < 0 || from > text.length()) {
      throw new IndexOutOfBoundsException("offset " + from + " of " + text.length());
    }
    Matcher m = pattern.matcher(new CheckedChars(text, token));
    m.useTransparentBounds(true);
    m.useAnchoringBounds(false);
    return m;
  }

  /** Text read through a cancellation check every {@link #CHECK_CHARS} chars. */
  private static final class CheckedChars implements CharSequence {
    private final CharSequence text;
    private final CancellationToken token;
    private int reads;

    CheckedChars(CharSequence text, CancellationToken token) {
      this.text = text;
      this.token = token;
    }

    @Override
    public int length() {
      return text.length();
    }

    @Override
    public char charAt(int index) {
      if (++reads >= CHECK_CHARS) {
        reads = 0;
        token.check();
      }
      return text.charAt(index);
    }

    @Override
    public CharSequence subSequence(int start, int end) {
      return text.subSequence(start, end).toString();
    }

    @Override
    public String toString() {
      return text.toString();
    }
  }
}
