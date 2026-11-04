package io.github.ajdailey.qcaret.app.session.archfixture;

import io.github.ajdailey.qcaret.app.search.archfixture.CycleStart;

/** The other half of a package cycle; never instantiated. */
public final class CycleEnd {
  private CycleEnd() {}

  /**
   * Uses the first half.
   *
   * @return the first half's name
   */
  public static String other() {
    return CycleStart.other();
  }
}
