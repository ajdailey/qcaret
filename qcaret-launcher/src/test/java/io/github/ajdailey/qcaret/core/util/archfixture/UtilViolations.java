package io.github.ajdailey.qcaret.core.util.archfixture;

import io.github.ajdailey.qcaret.core.text.LineEnding;

/** Utility classes that each break one architecture rule; never instantiated. */
final class UtilViolations {
  private UtilViolations() {}

  /** Uses another core package. */
  static final class UsesText {
    String lf() {
      return LineEnding.LF.text();
    }
  }
}
