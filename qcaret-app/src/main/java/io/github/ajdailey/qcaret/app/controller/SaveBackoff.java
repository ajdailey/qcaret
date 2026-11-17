package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.session.SessionStore;

/**
 * The delay before a session save retries a failed write: {@link SessionController#DELAY_MILLIS}
 * after the first {@link SessionStore.Write#FAILED}, doubling with each further one up to {@link
 * #MAX_MILLIS}. Any other outcome ends the run, and no retry follows a {@link
 * SessionStore.Write#REFUSED}. Not synchronized; the caller serializes access.
 */
final class SaveBackoff {
  /** The longest delay. */
  static final int MAX_MILLIS = 60_000;

  private int failures;

  /** Records the outcome of one write. */
  void record(SessionStore.Write outcome) {
    failures = outcome == SessionStore.Write.FAILED ? failures + 1 : 0;
  }

  /**
   * Returns the delay before the retry.
   *
   * @return milliseconds, or 0 when the last write did not fail
   */
  int delay() {
    if (failures == 0) {
      return 0;
    }
    long delay = (long) SessionController.DELAY_MILLIS << Math.min(failures - 1, 20);
    return (int) Math.min(delay, MAX_MILLIS);
  }
}
