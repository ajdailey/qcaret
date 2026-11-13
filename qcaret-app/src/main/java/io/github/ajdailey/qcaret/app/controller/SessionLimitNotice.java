package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.session.Session;
import io.github.ajdailey.qcaret.app.session.SessionOverflow;
import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import io.github.ajdailey.qcaret.core.concurrent.WorkerThread;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reports the first session save of a run that leaves entries out past {@code SessionStore}'s
 * limits: one WARN with every {@link SessionOverflow} count, and one notice in the active window
 * naming the kinds left out. Later saves report nothing. Thread-safe.
 */
final class SessionLimitNotice {
  private static final Logger LOG = LoggerFactory.getLogger(SessionLimitNotice.class);

  private final WindowSet set;
  private final UiDispatcher ui;
  private final AtomicBoolean reported = new AtomicBoolean();

  SessionLimitNotice(WindowSet set, UiDispatcher ui) {
    this.set = set;
    this.ui = ui;
  }

  /**
   * Reports {@code saved} unless it fits or a save of this run was reported.
   *
   * @param saved the session just written
   */
  @WorkerThread
  void check(Session saved) {
    SessionOverflow overflow = SessionOverflow.of(saved);
    if (overflow.isEmpty() || !reported.compareAndSet(false, true)) {
      return;
    }
    LOG.warn(
        "Session saved without {} windows, {} tabs, {} retained searches, {} expanded folders,"
            + " {} Recent Files and {} closed files past its limits",
        overflow.windows(),
        overflow.tabs(),
        overflow.searches(),
        overflow.expanded(),
        overflow.recent(),
        overflow.closed());
    final String notice = UiText.SESSION_LIMITS.format(String.join(", ", kinds(overflow)));
    ui.asyncExec(
        new Runnable() {
          @Override
          public void run() {
            ApplicationController window = set.target();
            if (window != null) {
              window.showNotice(notice);
            }
          }
        });
  }

  /** Returns each kind left out with its count. */
  static List<String> kinds(SessionOverflow overflow) {
    List<String> kinds = new ArrayList<String>();
    add(kinds, UiText.SESSION_LIMIT_WINDOWS, overflow.windows());
    add(kinds, UiText.SESSION_LIMIT_TABS, overflow.tabs());
    add(kinds, UiText.SESSION_LIMIT_SEARCHES, overflow.searches());
    add(kinds, UiText.SESSION_LIMIT_EXPANDED, overflow.expanded());
    add(kinds, UiText.SESSION_LIMIT_RECENT, overflow.recent());
    add(kinds, UiText.SESSION_LIMIT_CLOSED, overflow.closed());
    return kinds;
  }

  private static void add(List<String> kinds, UiText text, int count) {
    if (count > 0) {
      kinds.add(text.format(count));
    }
  }
}
