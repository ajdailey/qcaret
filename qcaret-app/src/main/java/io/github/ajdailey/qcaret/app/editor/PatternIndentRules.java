package io.github.ajdailey.qcaret.app.editor;

import io.github.ajdailey.qcaret.core.text.BoundedChars;
import java.util.regex.Pattern;

/**
 * Indent rules from a grammar's {@code indent} regexes, each found anywhere in its input. {@link
 * #increase} and {@link #dedentNext} read the last {@value #MAX_CHARS} chars before the caret and
 * {@link #decrease} the first {@value #MAX_CHARS} of the line, each through a {@link BoundedChars};
 * a regex that reaches the read limit or overflows the stack reports false. Confined to the UI
 * thread.
 */
public final class PatternIndentRules implements IndentRules {
  /** Chars of a line a rule reads. */
  static final int MAX_CHARS = 1000;

  private final Pattern increase;
  private final Pattern decrease;
  private final Pattern dedentNext;
  private final BoundedChars chars = new BoundedChars();

  /**
   * Creates the rules.
   *
   * @param increase a match before the caret indents the next line, or null for never
   * @param decrease a match in a line outdents it, or null for never
   * @param dedentNext a match before the caret outdents the next line, or null for never
   */
  public PatternIndentRules(Pattern increase, Pattern decrease, Pattern dedentNext) {
    this.increase = increase;
    this.decrease = decrease;
    this.dedentNext = dedentNext;
  }

  @Override
  public boolean increase(CharSequence before) {
    int from = Math.max(0, before.length() - MAX_CHARS);
    return find(increase, before.subSequence(from, before.length()));
  }

  @Override
  public boolean dedentNext(CharSequence before) {
    int from = Math.max(0, before.length() - MAX_CHARS);
    return find(dedentNext, before.subSequence(from, before.length()));
  }

  @Override
  public boolean decrease(CharSequence line) {
    return find(decrease, line.subSequence(0, Math.min(line.length(), MAX_CHARS)));
  }

  private boolean find(Pattern pattern, CharSequence text) {
    if (pattern == null) {
      return false;
    }
    try {
      return pattern.matcher(chars.reset(text, BoundedChars.lineLimit(text))).find();
    } catch (BoundedChars.LimitReached | StackOverflowError e) {
      return false;
    }
  }
}
