package io.github.ajdailey.qcaret.ui.swt.platform;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;

/**
 * Dialog tabs on win32 after a dark start: a flat {@link CTabFolder} whose row, unselected tabs and
 * pages take the {@link Win32DialogColors} body color and whose selected tab takes the field color,
 * with white text on every tab. The native {@code TabFolder} keeps a light tab row and a white
 * frame around the page under the dark native theme, and no public API colors either. {@code
 * CTabFolder} draws the selected tab's color only while focus is in the folder, else the row's.
 * Ctrl+PageDown and Ctrl+PageUp reach the folder from a page control, as they reach the native
 * folder.
 */
final class Win32DialogTabs implements DialogTabs {
  private final CTabFolder folder;

  /**
   * Creates the folder.
   *
   * @param parent the dialog composite holding it
   */
  Win32DialogTabs(Composite parent) {
    folder = new CTabFolder(parent, SWT.TOP | SWT.FLAT);
    folder.setBackground(Win32DialogColors.WINDOW);
    folder.setForeground(Win32DialogColors.TEXT);
    folder.setSelectionBackground(Win32DialogColors.FIELD);
    folder.setSelectionForeground(Win32DialogColors.TEXT);
  }

  @Override
  public Composite control() {
    return folder;
  }

  @Override
  public Composite add(String title) {
    CTabItem item = new CTabItem(folder, SWT.NONE);
    item.setText(title);
    Composite page = new Composite(folder, SWT.NONE);
    page.setBackground(Win32DialogColors.WINDOW);
    item.setControl(page);
    if (folder.getItemCount() == 1) {
      folder.setSelection(0);
    }
    return page;
  }

  @Override
  public Composite page(int index) {
    return (Composite) folder.getItem(index).getControl();
  }

  @Override
  public void select(int index) {
    folder.setSelection(index);
  }

  @Override
  public int selection() {
    return folder.getSelectionIndex();
  }

  @Override
  public int count() {
    return folder.getItemCount();
  }

  @Override
  public void addSelectionListener(final Runnable listener) {
    folder.addListener(
        SWT.Selection,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            listener.run();
          }
        });
  }
}
