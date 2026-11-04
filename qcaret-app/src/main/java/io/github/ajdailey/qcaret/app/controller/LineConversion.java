package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.core.buffer.ChunkVisitor;
import io.github.ajdailey.qcaret.core.buffer.TextSnapshot;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.WorkerThread;
import io.github.ajdailey.qcaret.core.document.Replacement;
import io.github.ajdailey.qcaret.core.text.LineEnding;

/**
 * A whole-text replacement that writes every line break as one ending, and where a caret lands in
 * the new text.
 *
 * @param replacement replaces the whole text
 * @param caret the offset in the new text of the caret given to {@link #convert}
 */
record LineConversion(Replacement replacement, int caret) {
  /** Chars between cancellation checks. */
  private static final int CHECK_CHARS = 1 << 16;

  /**
   * Converts a text's line breaks; CR LF, CR and LF each count as one break.
   *
   * @param text the text
   * @param ending the ending every break becomes
   * @param caret an offset in {@code text}
   * @param token checked every {@value #CHECK_CHARS} chars
   * @return the conversion
   * @throws java.util.concurrent.CancellationException when canceled
   */
  @WorkerThread
  static LineConversion convert(
      TextSnapshot text, LineEnding ending, final int caret, final CancellationToken token) {
    final String breakText = ending.text();
    final StringBuilder out = new StringBuilder(text.length() + text.length() / 16);
    // [0] a CR is pending, [1] the caret's new offset, [2] chars left before a check, [3] the
    // offset of the next char read.
    final int[] state = {0, -1, CHECK_CHARS, 0};
    text.readChunks(
        0,
        text.length(),
        new ChunkVisitor() {
          @Override
          public void chunk(CharSequence chunk, int start, int end) {
            for (int i = start; i < end; i++) {
              if (state[3]++ == caret) {
                state[1] = out.length();
              }
              char c = chunk.charAt(i);
              if (state[0] == 1) {
                state[0] = 0;
                if (c == '\n') {
                  continue;
                }
              }
              if (c == '\r') {
                out.append(breakText);
                state[0] = 1;
              } else if (c == '\n') {
                out.append(breakText);
              } else {
                out.append(c);
              }
              if (--state[2] == 0) {
                token.check();
                state[2] = CHECK_CHARS;
              }
            }
          }
        });
    int newCaret = state[1] < 0 ? out.length() : state[1];
    return new LineConversion(new Replacement(0, text.length(), out.toString()), newCaret);
  }
}
