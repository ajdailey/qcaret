package io.github.ajdailey.qcaret.ui.swt;

import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.view.RecoveryInput;
import io.github.ajdailey.qcaret.app.view.RecoveryRow;
import java.util.List;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;

/**
 * Lists a dead instance's unsaved buffers: file, write time and how the original file stands.
 * Restore and Discard act on the selected rows, all of them at first; Later and closing the dialog
 * keep the rest for the next start. Modeless, so restored tabs take input while it shows. It closes
 * when no row is left.
 */
final class RecoveryDialog {
  private final Shell shell;
  private final Table table;
  private final RecoveryInput input;
  private boolean decided;

  RecoveryDialog(Shell parent, RecoveryInput input) {
    this.input = input;
    shell = Dialogs.shell(parent, UiText.RECOVERY_TITLE.text(), SWT.DIALOG_TRIM | SWT.RESIZE);
    Label label = Dialogs.label(shell, UiText.RECOVERY_MESSAGE.text());
    ((GridData) label.getLayoutData()).widthHint = 560;
    table = new Table(shell, SWT.BORDER | SWT.MULTI | SWT.FULL_SELECTION);
    table.setHeaderVisible(true);
    GridData data = new GridData(SWT.FILL, SWT.FILL, true, true);
    data.heightHint = 160;
    table.setLayoutData(data);
    column(UiText.RECOVERY_COLUMN_FILE, 320);
    column(UiText.RECOVERY_COLUMN_TIME, 150);
    column(UiText.RECOVERY_COLUMN_STATE, 170);
    Composite row = Dialogs.buttonRow(shell, 1);
    shell.setDefaultButton(
        Dialogs.button(
            row,
            UiText.RECOVERY_RESTORE.text(),
            new Runnable() {
              @Override
              public void run() {
                restore();
              }
            }));
    Dialogs.button(
        row,
        UiText.RECOVERY_DISCARD.text(),
        new Runnable() {
          @Override
          public void run() {
            discard();
          }
        });
    Dialogs.button(row, UiText.RECOVERY_LATER.text(), Dialogs.closer(shell));
    shell.addListener(
        SWT.Dispose,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            if (!decided) {
              decided = true;
              RecoveryDialog.this.input.later();
            }
          }
        });
    fill();
    table.selectAll();
  }

  private void column(UiText title, int width) {
    TableColumn column = new TableColumn(table, SWT.LEFT);
    column.setText(title.text());
    column.setWidth(width);
  }

  private void fill() {
    table.removeAll();
    for (RecoveryRow row : input.rows()) {
      TableItem item = new TableItem(table, SWT.NONE);
      item.setText(new String[] {row.name(), row.time(), row.state()});
    }
  }

  void open() {
    Dialogs.open(shell, SWT.DEFAULT, SWT.DEFAULT);
  }

  Shell shell() {
    return shell;
  }

  /** Shows the rows left; closes the dialog when there are none. */
  void refresh() {
    if (shell.isDisposed()) {
      return;
    }
    List<RecoveryRow> rows = input.rows();
    if (rows.isEmpty()) {
      decided = true;
      shell.dispose();
      return;
    }
    fill();
    table.selectAll();
  }

  /** Restores the selected rows; also for tests. */
  void restore() {
    input.restore(table.getSelectionIndices());
  }

  /** Discards the selected rows; also for tests. */
  void discard() {
    input.discard(table.getSelectionIndices());
  }

  /**
   * Discards one row, for tests.
   *
   * @param index the row
   */
  void discardRow(int index) {
    table.setSelection(index);
    discard();
  }

  /**
   * Returns the rows shown.
   *
   * @return the row count
   */
  int rowCount() {
    return table.getItemCount();
  }
}
