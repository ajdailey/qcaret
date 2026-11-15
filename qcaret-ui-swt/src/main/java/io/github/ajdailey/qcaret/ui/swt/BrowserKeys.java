package io.github.ajdailey.qcaret.ui.swt;

import io.github.ajdailey.qcaret.app.view.WorkspaceSelectionInput;
import org.eclipse.swt.SWT;

/**
 * The Workspace Browser's keys: Delete deletes and F2 renames, without a modifier. On macOS the key
 * labeled delete sends Backspace, so Backspace deletes there too, beside fn-Delete.
 */
final class BrowserKeys {
  private BrowserKeys() {}

  /**
   * Returns the action a key runs on the browser's selection.
   *
   * @param keyCode the event's key code
   * @param stateMask the event's state mask
   * @param mac true when key names follow macOS
   * @return the action, or null when the key runs none
   */
  static WorkspaceSelectionInput.Action action(int keyCode, int stateMask, boolean mac) {
    if ((stateMask & SWT.MODIFIER_MASK) != 0) {
      return null;
    }
    if (keyCode == SWT.DEL || (mac && keyCode == SWT.BS)) {
      return WorkspaceSelectionInput.Action.DELETE;
    }
    return keyCode == SWT.F2 ? WorkspaceSelectionInput.Action.RENAME : null;
  }
}
