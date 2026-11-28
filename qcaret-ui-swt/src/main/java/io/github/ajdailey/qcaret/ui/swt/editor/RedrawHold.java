package io.github.ajdailey.qcaret.ui.swt.editor;

/**
 * An {@link EditorPane}'s redraw hold ({@link EditorPane#holdRedraw}): while held, the widget is
 * under {@code setRedraw(false)} and the pane records the redraws it skips as bits; the release
 * returns them, so the pane shows them once.
 */
final class RedrawHold {
  /** The widget's selection sync, which also redraws. */
  static final int SYNC = 1;

  /** Scrolling to the caret. */
  static final int CARET = 2;

  /** A highlight redraw. */
  static final int REDRAW = 4;

  private boolean held;
  private int deferred;

  /**
   * Records a redraw while held.
   *
   * @param kind {@link #SYNC}, {@link #CARET} or {@link #REDRAW}
   * @return true when held, so the caller skips the redraw
   */
  boolean defer(int kind) {
    if (held) {
      deferred |= kind;
    }
    return held;
  }

  /** Reports whether the pane holds its redraws. */
  boolean held() {
    return held;
  }

  /**
   * Holds or releases.
   *
   * @param value true to hold
   * @return on a release, the kinds skipped since the hold; else 0
   */
  int set(boolean value) {
    held = value;
    if (value) {
      return 0;
    }
    int skipped = deferred;
    deferred = 0;
    return skipped;
  }
}
