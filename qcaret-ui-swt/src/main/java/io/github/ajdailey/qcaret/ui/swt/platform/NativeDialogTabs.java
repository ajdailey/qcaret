package io.github.ajdailey.qcaret.ui.swt.platform;

import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.TabFolder;
import org.eclipse.swt.widgets.TabItem;

/** Dialog tabs on GTK, cocoa and win32 after a light start: the native {@link TabFolder}. */
final class NativeDialogTabs implements DialogTabs {
  private final TabFolder folder;

  /**
   * Creates the folder.
   *
   * @param parent the dialog composite holding it
   */
  NativeDialogTabs(Composite parent) {
    folder = new TabFolder(parent, SWT.TOP);
  }

  @Override
  public Composite control() {
    return folder;
  }

  @Override
  public Composite add(String title) {
    TabItem item = new TabItem(folder, SWT.NONE);
    item.setText(title);
    Composite page = new Composite(folder, SWT.NONE);
    item.setControl(page);
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
