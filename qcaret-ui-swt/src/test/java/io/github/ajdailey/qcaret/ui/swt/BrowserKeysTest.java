package io.github.ajdailey.qcaret.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.github.ajdailey.qcaret.app.view.WorkspaceSelectionInput;
import org.eclipse.swt.SWT;
import org.junit.jupiter.api.Test;

/** RM-1: the Mac keyboard's delete key sends Backspace, which deletes in the browser on macOS. */
class BrowserKeysTest {
  @Test
  void deleteDeletesEverywhereAndBackspaceOnlyOnMacOs() {
    assertEquals(WorkspaceSelectionInput.Action.DELETE, BrowserKeys.action(SWT.DEL, 0, false));
    assertEquals(WorkspaceSelectionInput.Action.DELETE, BrowserKeys.action(SWT.DEL, 0, true));
    assertEquals(WorkspaceSelectionInput.Action.DELETE, BrowserKeys.action(SWT.BS, 0, true));
    assertNull(BrowserKeys.action(SWT.BS, 0, false));
  }

  @Test
  void f2RenamesAndAModifierRunsNothing() {
    assertEquals(WorkspaceSelectionInput.Action.RENAME, BrowserKeys.action(SWT.F2, 0, true));
    assertNull(BrowserKeys.action(SWT.BS, SWT.COMMAND, true));
    assertNull(BrowserKeys.action(SWT.DEL, SWT.SHIFT, false));
    assertNull(BrowserKeys.action(SWT.F2, SWT.ALT, false));
    assertNull(BrowserKeys.action('a', 0, true));
  }
}
