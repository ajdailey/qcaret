package io.github.ajdailey.qcaret.app.controller;

import java.util.ArrayList;
import java.util.List;

/**
 * The order in which one window's tabs were last shown, and the tab shown after a close. Closing
 * the shown tab shows the tab after it, else the one before; with {@code tabs.closeShowsLastUsed}
 * on, the most recently shown open tab. A bulk close closes the shown tab last, so no other tab is
 * shown on the way. Confined to the UI thread.
 */
final class TabUse {
  /** Tabs most recently shown first; a closed or moved-out tab is not listed. */
  private final List<OpenDocument> order = new ArrayList<OpenDocument>();

  private boolean lastUsed;

  /** Sets {@code tabs.closeShowsLastUsed}. */
  void setLastUsed(boolean on) {
    lastUsed = on;
  }

  /** Records a tab as the one shown now. */
  void shown(OpenDocument d) {
    if (order.isEmpty() || order.get(0) != d) {
      order.remove(d);
      order.add(0, d);
    }
  }

  /** Drops a closed or moved-out tab. */
  void removed(OpenDocument d) {
    order.remove(d);
  }

  /**
   * Returns the tab to show after the shown one closed.
   *
   * @param open the open tabs in tab order, the closed one already removed
   * @param index the closed tab's former index
   * @return the tab, or null when none is open
   */
  OpenDocument successor(List<OpenDocument> open, int index) {
    if (open.isEmpty()) {
      return null;
    }
    if (lastUsed) {
      for (OpenDocument d : order) {
        if (open.contains(d)) {
          return d;
        }
      }
    }
    return open.get(Math.min(index, open.size() - 1));
  }

  /**
   * Returns the next tab a bulk close closes: the first in order apart from the shown tab while it
   * is clean, and the shown tab once it is the only one left. A dirty tab keeps its place, so the
   * unsaved prompts come in tab order.
   *
   * @param candidates the tabs still to close, in tab order; closed ones are skipped
   * @param shown the shown tab, or null
   * @return the tab, or null when none is left
   */
  static OpenDocument nextToClose(List<OpenDocument> candidates, OpenDocument shown) {
    boolean deferred = false;
    for (OpenDocument d : candidates) {
      if (d.closed) {
        continue;
      }
      if (d == shown && !d.document.isDirty()) {
        deferred = true;
      } else {
        return d;
      }
    }
    return deferred ? shown : null;
  }
}
