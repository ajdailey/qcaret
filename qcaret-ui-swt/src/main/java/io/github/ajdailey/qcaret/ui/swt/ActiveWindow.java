package io.github.ajdailey.qcaret.ui.swt;

import org.eclipse.swt.widgets.Shell;

/** Picks the window a main window's command acts on. */
final class ActiveWindow {
  private ActiveWindow() {}

  /**
   * Returns the active shell when a main window owns it, else the main window.
   *
   * @param main the main window's shell
   * @param active the Display's active shell, or null
   * @return {@code active} when it is {@code main} or a shell {@code main} owns at any depth, else
   *     {@code main}
   */
  static Shell ownedBy(Shell main, Shell active) {
    for (Shell at = active; at != null && !at.isDisposed(); ) {
      if (at == main) {
        return active;
      }
      at = at.getParent() instanceof Shell owner ? owner : null;
    }
    return main;
  }
}
