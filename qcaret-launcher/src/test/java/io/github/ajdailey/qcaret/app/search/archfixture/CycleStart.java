package io.github.ajdailey.qcaret.app.search.archfixture;

import io.github.ajdailey.qcaret.app.session.archfixture.CycleEnd;

/** One half of a package cycle; never instantiated. */
public final class CycleStart {
  private CycleStart() {}

  /**
   * Uses the other half.
   *
   * @return the other half's name
   */
  public static String other() {
    return CycleEnd.class.getName();
  }
}
