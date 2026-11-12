package io.github.ajdailey.qcaret.app.config;

/**
 * Help > Tip of the Day's settings in {@code qcaret.conf}. Immutable.
 *
 * @param onStartup {@code help.tipsOnStartup}: the dialog opens once the session has restored
 * @param next {@code help.nextTip}: the index of the tip shown next, 0 to {@link #MAX_NEXT}; the
 *     tip list wraps it
 */
public record TipSettings(boolean onStartup, int next) {
  /** Largest {@code next}. */
  public static final int MAX_NEXT = 9999;

  /** Off, starting at the first tip. */
  public static final TipSettings DEFAULTS = new TipSettings(false, 0);

  /** Checks the components. */
  public TipSettings {
    if (next < 0 || next > MAX_NEXT) {
      throw new IllegalArgumentException("next out of range");
    }
  }

  /**
   * Returns a copy shown or not at startup.
   *
   * @param value true to show the dialog at startup
   * @return the copy
   */
  public TipSettings withOnStartup(boolean value) {
    return new TipSettings(value, next);
  }

  /**
   * Returns a copy with another next tip.
   *
   * @param value the index, 0 to {@link #MAX_NEXT}
   * @return the copy
   */
  public TipSettings withNext(int value) {
    return new TipSettings(onStartup, value);
  }
}
