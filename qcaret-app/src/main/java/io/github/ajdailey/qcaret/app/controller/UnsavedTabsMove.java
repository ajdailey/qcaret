package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.core.document.Document;
import java.util.List;

/**
 * Moves a closing window's unsaved tabs into another window, for a window close with {@code
 * session.snapshot} on. Confined to the UI thread.
 */
final class UnsavedTabsMove {
  private static final WindowSet.Placement END =
      new WindowSet.Placement(Integer.MAX_VALUE, null, null);

  private final WindowSet set;

  /**
   * Creates the move over a set.
   *
   * @param set the windows
   */
  UnsavedTabsMove(WindowSet set) {
    this.set = set;
  }

  /**
   * Moves each unsaved tab of a window, in tab order, after the last tab of the other window
   * activated last, as {@link WindowSet#moveDocument} does. A tab that cannot move ({@link
   * DocumentController#canMove}) stays, with no notice.
   *
   * @param closing the window
   */
  void move(ApplicationController closing) {
    List<ApplicationController> order = set.activationOrder();
    ApplicationController target = null;
    for (int i = order.size() - 1; i >= 0 && target == null; i--) {
      if (order.get(i) != closing) {
        target = order.get(i);
      }
    }
    if (target == null) {
      return;
    }
    DocumentController documents = closing.documents();
    for (Document document : documents.openDocuments()) {
      if (document.isDirty() && documents.canMove(document)) {
        set.moveDocument(closing, document, target, END);
      }
    }
  }
}
