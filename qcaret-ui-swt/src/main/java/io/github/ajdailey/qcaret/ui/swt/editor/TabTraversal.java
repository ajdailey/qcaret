package io.github.ajdailey.qcaret.ui.swt.editor;

import io.github.ajdailey.qcaret.app.command.KeyStroke;
import io.github.ajdailey.qcaret.app.command.Keymap;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Event;

/**
 * Keeps a Tab stroke the editor keymap binds in the editor. {@code StyledText.handleTraverse}
 * traverses focus on Tab with any modifier held, so Shift+Tab leaves the editor before the key
 * listener runs Outdent. A Tab stroke with no editor binding, Ctrl+Tab by default, still traverses.
 */
final class TabTraversal {
  private TabTraversal() {}

  /**
   * Cancels a Tab traversal whose stroke has an editor binding, so the key reaches the editor.
   *
   * @param event an {@code SWT.Traverse} event, after the widget's own listener
   * @param keymap the editor's key bindings
   */
  static void keepBound(Event event, Keymap keymap) {
    if (event.detail != SWT.TRAVERSE_TAB_NEXT && event.detail != SWT.TRAVERSE_TAB_PREVIOUS) {
      return;
    }
    KeyStroke stroke = SwtKeys.stroke(event.stateMask, SWT.TAB, '\t');
    if (stroke != null && keymap.editorCommand(stroke) != null) {
      event.doit = false;
    }
  }
}
