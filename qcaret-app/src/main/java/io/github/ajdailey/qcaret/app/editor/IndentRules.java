package io.github.ajdailey.qcaret.app.editor;

/**
 * When smart indent adds or removes one indent level ({@link SmartIndent}). A grammar's {@code
 * indent} increase, decrease and dedentNext patterns implement it; {@link #BRACKETS} serves every
 * other document.
 */
public interface IndentRules {
  /** Increase after a line ending in {@code {}, {@code (} or {@code [}; decrease before a closer. */
  IndentRules BRACKETS =
      new IndentRules() {
        @Override
        public boolean increase(CharSequence before) {
          for (int i = before.length() - 1; i >= 0; i--) {
            char c = before.charAt(i);
            if (c != ' ' && c != '\t') {
              return c == '{' || c == '(' || c == '[';
            }
          }
          return false;
        }

        @Override
        public boolean dedentNext(CharSequence before) {
          return false;
        }

        @Override
        public boolean decrease(CharSequence line) {
          for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c != ' ' && c != '\t') {
              return c == '}' || c == ')' || c == ']';
            }
          }
          return false;
        }
      };

  /**
   * Reports whether the line after Enter takes one more indent level.
   *
   * @param before the line's text before the caret
   * @return true after an opener
   */
  boolean increase(CharSequence before);

  /**
   * Reports whether the line after Enter takes one indent level less, as after a statement that
   * ends a block ({@code return} in Python). {@link #increase} wins when both hold.
   *
   * @param before the line's text before the caret
   * @return true after a block-ending statement
   */
  boolean dedentNext(CharSequence before);

  /**
   * Reports whether a line takes one indent level less than the line it follows.
   *
   * @param line a line's text from its start, leading whitespace included
   * @return true when the line starts with a closer
   */
  boolean decrease(CharSequence line);
}
