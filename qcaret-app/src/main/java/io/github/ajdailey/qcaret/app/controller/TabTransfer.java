package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.view.WindowGeometry;
import io.github.ajdailey.qcaret.core.document.Document;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Tab moves between the windows of a {@link WindowSet} and the listeners that run after each one.
 * The contract is on {@link WindowSet#moveDocument}. Confined to the UI thread.
 */
final class TabTransfer {
  private final WindowSet set;
  private final List<Runnable> moveListeners = new ArrayList<Runnable>();

  /**
   * Creates the moves over a set.
   *
   * @param set the windows
   */
  TabTransfer(WindowSet set) {
    this.set = set;
  }

  /** See {@link WindowSet#moveDocument}. */
  ApplicationController moveDocument(
      ApplicationController source,
      Document document,
      ApplicationController target,
      WindowSet.Placement placement) {
    List<ApplicationController> windows = set.windows();
    if (set.isQuitting()
        || target == source
        || !windows.contains(source)
        || !source.documents().isOpen(document)
        || (target != null && !windows.contains(target))) {
      return null;
    }
    String name = source.documents().nameOf(document);
    if (!source.documents().canMove(document)) {
      source.showNotice(UiText.NOTICE_MOVE_BUSY.format(name));
      return null;
    }
    Path path = source.documents().pathOf(document);
    if (target != null && path != null && target.documents().documentAt(path) != null) {
      source.showNotice(UiText.NOTICE_MOVE_SHOWN.format(name));
      return null;
    }
    ApplicationController destination = target;
    if (destination == null) {
      destination =
          set.openWindow(
              new WindowGeometry.Request(placement.x(), placement.y(), null, null), false);
      if (destination == null) {
        return null;
      }
    }
    source.documents().moveTo(document, destination.documents(), placement.index());
    if (target != null) {
      set.activated(target);
      target.toFront();
    }
    for (Runnable listener : new ArrayList<Runnable>(moveListeners)) {
      listener.run();
    }
    return destination;
  }

  /** See {@link WindowSet#addMoveListener}. */
  void addMoveListener(Runnable listener) {
    moveListeners.add(listener);
  }
}
