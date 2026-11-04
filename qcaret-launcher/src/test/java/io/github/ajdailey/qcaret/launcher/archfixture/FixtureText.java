package io.github.ajdailey.qcaret.launcher.archfixture;

/** Strings of which one is never read. */
enum FixtureText {
  /** Read by {@link Reader}. */
  USED,
  /** Read by nothing. */
  UNUSED;

  /** Reads one constant. */
  static final class Reader {
    private Reader() {}

    static String used() {
      return USED.name();
    }
  }
}
