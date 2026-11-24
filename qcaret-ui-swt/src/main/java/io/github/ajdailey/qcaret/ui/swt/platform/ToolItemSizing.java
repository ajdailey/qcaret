package io.github.ajdailey.qcaret.ui.swt.platform;

import org.eclipse.swt.widgets.ToolItem;

/**
 * Gives a tool item room for its image where the SWT backend lays it out too small, from {@link
 * PlatformTraits#toolItemSizing}: {@link Gtk4ToolItemSizing} on GTK 4, {@link NativeToolItemSizing}
 * elsewhere. Call on the UI thread.
 */
public interface ToolItemSizing {
  /** The running platform's sizing. */
  ToolItemSizing PLATFORM = PlatformTraits.PLATFORM.toolItemSizing();

  /**
   * Prepares an item for an image of its icon's size; call before {@code ToolItem.setImage}.
   *
   * @param item the item
   */
  void keepIconSize(ToolItem item);
}
