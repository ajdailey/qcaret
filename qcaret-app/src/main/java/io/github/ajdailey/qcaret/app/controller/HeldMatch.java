package io.github.ajdailey.qcaret.app.controller;

/** A find match waiting for the preview to show a render of the match's revision. */
final class HeldMatch {
  private boolean held;
  private int start;
  private int end;
  private long revision;

  /**
   * Holds a match, replacing the one held.
   *
   * @param start the match's start
   * @param end the match's end
   * @param revision the document revision the match was found in
   */
  void hold(int start, int end, long revision) {
    this.held = true;
    this.start = start;
    this.end = end;
    this.revision = revision;
  }

  /**
   * Takes the match when it applies to the render shown; drops it once the document has moved past
   * its revision or the preview has left Preview.
   *
   * @param shown the revision of the render shown
   * @param now the document's revision
   * @param preview true while the mode is Preview
   * @return true when the caller shows the match now
   */
  boolean take(long shown, long now, boolean preview) {
    if (!held) {
      return false;
    }
    if (revision != now || !preview) {
      held = false;
      return false;
    }
    if (shown != revision) {
      return false;
    }
    held = false;
    return true;
  }

  /** Returns the held match's start. */
  int start() {
    return start;
  }

  /** Returns the held match's end. */
  int end() {
    return end;
  }
}
