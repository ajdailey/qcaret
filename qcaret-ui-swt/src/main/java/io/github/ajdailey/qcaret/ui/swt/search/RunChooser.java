package io.github.ajdailey.qcaret.ui.swt.search;

import io.github.ajdailey.qcaret.app.i18n.UiText;
import java.util.List;
import java.util.function.Consumer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.layout.RowLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;

/**
 * Asks which replace run to restore: the runs kept, newest first, with Restore and Cancel. Modal,
 * opened without a nested event loop; Enter or a double click restores the selected run.
 */
public final class RunChooser {
  private final Shell shell;
  private final org.eclipse.swt.widgets.List list;
  private final Consumer<Integer> chosen;

  /**
   * Creates the dialog.
   *
   * @param parent the main window
   * @param runs one line per run
   * @param chosen receives the chosen run's index
   */
  public RunChooser(Shell parent, List<String> runs, Consumer<Integer> chosen) {
    this.chosen = chosen;
    shell = new Shell(parent, SWT.DIALOG_TRIM | SWT.RESIZE | SWT.APPLICATION_MODAL);
    shell.setText(UiText.RESTORE_CHOOSE_TITLE.text());
    GridLayout layout = new GridLayout(1, false);
    layout.marginWidth = 12;
    layout.marginHeight = 12;
    layout.verticalSpacing = 8;
    shell.setLayout(layout);
    Label label = new Label(shell, SWT.WRAP);
    label.setText(UiText.RESTORE_CHOOSE_LABEL.text());
    GridData labelData = new GridData(SWT.FILL, SWT.CENTER, true, false);
    labelData.widthHint = 420;
    label.setLayoutData(labelData);
    list = new org.eclipse.swt.widgets.List(shell, SWT.SINGLE | SWT.BORDER | SWT.V_SCROLL);
    GridData listData = new GridData(SWT.FILL, SWT.FILL, true, true);
    listData.heightHint = 200;
    list.setLayoutData(listData);
    for (String run : runs) {
      list.add(run);
    }
    list.select(0);
    list.addListener(
        SWT.DefaultSelection,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            choose();
          }
        });
    Composite buttons = new Composite(shell, SWT.NONE);
    RowLayout row = new RowLayout(SWT.HORIZONTAL);
    row.marginLeft = 0;
    row.marginRight = 0;
    row.spacing = 8;
    buttons.setLayout(row);
    buttons.setLayoutData(new GridData(SWT.END, SWT.CENTER, true, false));
    Button restore = new Button(buttons, SWT.PUSH);
    restore.setText(UiText.REPLACE_RESTORE.text());
    restore.addListener(
        SWT.Selection,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            choose();
          }
        });
    Button cancel = new Button(buttons, SWT.PUSH);
    cancel.setText(UiText.BUTTON_CANCEL.text());
    cancel.addListener(
        SWT.Selection,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            shell.dispose();
          }
        });
    shell.setDefaultButton(restore);
  }

  private void choose() {
    int at = list.getSelectionIndex();
    if (at >= 0) {
      // Close first, so the report opens over the main window, not behind this dialog.
      shell.dispose();
      chosen.accept(at);
    }
  }

  /** Opens the dialog over its parent. */
  public void open() {
    shell.pack();
    Point size = shell.getSize();
    Rectangle owner = shell.getParent().getBounds();
    shell.setLocation(
        owner.x + Math.max(0, (owner.width - size.x) / 2),
        owner.y + Math.max(0, (owner.height - size.y) / 2));
    shell.open();
  }

  /**
   * Returns the shell.
   *
   * @return the shell
   */
  public Shell shell() {
    return shell;
  }

  /**
   * Chooses a run, for tests.
   *
   * @param run the run's row
   */
  public void choose(int run) {
    list.select(run);
    choose();
  }
}
