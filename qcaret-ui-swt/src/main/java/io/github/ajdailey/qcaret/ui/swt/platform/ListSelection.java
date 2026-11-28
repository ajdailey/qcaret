package io.github.ajdailey.qcaret.ui.swt.platform;

import org.eclipse.swt.widgets.List;

/**
 * Draws the selected row of a single-selection {@link List}, from {@link
 * PlatformTraits#listSelection}: {@link Win32ListSelection} on win32, {@link NativeListSelection}
 * elsewhere. Call on the UI thread.
 */
public interface ListSelection {
  /** The running platform's implementation. */
  ListSelection PLATFORM = PlatformTraits.PLATFORM.listSelection();

  /**
   * Draws the list's selection from now on until the list is disposed; call once, after creating
   * the list. A {@code SWT.MULTI} list is left as it is.
   *
   * @param list the list
   */
  void draw(List list);
}
