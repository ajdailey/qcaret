package io.github.ajdailey.qcaret.core.text.archfixture;

import io.github.ajdailey.qcaret.core.buffer.TextSnapshot;

/** Text-layer classes that each break one architecture rule; never instantiated. */
final class TextViolations {
  private TextViolations() {}

  /** Uses the buffer layer, which sits above text. */
  static final class UsesBuffer {
    int length(TextSnapshot text) {
      return text.length();
    }
  }
}
