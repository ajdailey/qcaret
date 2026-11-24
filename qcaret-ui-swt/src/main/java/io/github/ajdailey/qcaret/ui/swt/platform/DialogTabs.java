package io.github.ajdailey.qcaret.ui.swt.platform;

import org.eclipse.swt.widgets.Composite;

/**
 * A dialog's tab folder, one implementation per platform: {@link NativeDialogTabs} is the native
 * {@code TabFolder}; {@link Win32DialogTabs} is a {@code CTabFolder} in the dark dialog colors. A
 * dialog takes one from {@link PlatformTraits#dialogTabs}. The first tab added is selected.
 * Confined to the UI thread.
 */
public interface DialogTabs {
  /**
   * Returns the folder, for its layout data.
   *
   * @return the folder control
   */
  Composite control();

  /**
   * Adds a tab after the last one.
   *
   * @param title the tab's text
   * @return the tab's page, without a layout, for the caller to fill
   */
  Composite add(String title);

  /**
   * Returns a tab's page.
   *
   * @param index the tab's index
   * @return the page {@link #add} returned
   */
  Composite page(int index);

  /**
   * Selects a tab without notifying the selection listeners.
   *
   * @param index the tab's index
   */
  void select(int index);

  /**
   * Returns the selected tab.
   *
   * @return its index, or -1 with no tab
   */
  int selection();

  /**
   * Returns the number of tabs.
   *
   * @return the count
   */
  int count();

  /**
   * Runs a listener each time the user selects a tab, by mouse or keyboard.
   *
   * @param listener reads {@link #selection} for the new tab
   */
  void addSelectionListener(Runnable listener);
}
