package io.github.ajdailey.qcaret.ui.swt.search;

import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.view.ReportInput;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.layout.RowLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;

/**
 * A replace or restore run's report: a summary over a virtual table of file, folder and result, a
 * skipped or failed row in the error color, with Stop while the run goes on, Restore Run once a
 * replace with backups ends, and Close. Modeless; closing it does not stop the run.
 */
public final class ReportWindow {
  private final Shell shell;
  private final ReportInput input;
  private final Label summary;
  private final Table table;
  private final Button stop;
  private final Button restore;

  /**
   * Creates the window.
   *
   * @param parent the main window
   * @param input the report
   */
  public ReportWindow(Shell parent, ReportInput input) {
    this.input = input;
    shell = new Shell(parent, SWT.SHELL_TRIM);
    shell.setText(input.title().replace("&", "&&"));
    GridLayout layout = new GridLayout(1, false);
    layout.marginWidth = 12;
    layout.marginHeight = 12;
    layout.verticalSpacing = 8;
    shell.setLayout(layout);
    summary = new Label(shell, SWT.WRAP);
    summary.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    table = new Table(shell, SWT.VIRTUAL | SWT.SINGLE | SWT.FULL_SELECTION | SWT.BORDER);
    table.setHeaderVisible(true);
    table.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
    column(UiText.REPLACE_FILE_COLUMN, 180);
    column(UiText.REPLACE_FOLDER_COLUMN, 280);
    column(UiText.REPLACE_RESULT_COLUMN, 320);
    table.addListener(
        SWT.SetData,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            load((TableItem) event.item, event.index);
          }
        });
    Composite buttons = new Composite(shell, SWT.NONE);
    RowLayout row = new RowLayout(SWT.HORIZONTAL);
    row.marginLeft = 0;
    row.marginRight = 0;
    row.spacing = 8;
    buttons.setLayout(row);
    buttons.setLayoutData(new GridData(SWT.END, SWT.CENTER, true, false));
    stop = button(buttons, UiText.REPLACE_STOP);
    restore = button(buttons, UiText.REPLACE_RESTORE);
    Button close = button(buttons, UiText.BUTTON_CLOSE);
    shell.setDefaultButton(close);
  }

  private void column(UiText label, int width) {
    TableColumn column = new TableColumn(table, SWT.LEFT);
    column.setText(label.text());
    column.setWidth(width);
  }

  private Button button(Composite parent, final UiText label) {
    Button button = new Button(parent, SWT.PUSH);
    button.setText(label.text());
    button.addListener(
        SWT.Selection,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            press(label);
          }
        });
    return button;
  }

  private void load(TableItem item, int index) {
    ReportInput.Row row = input.row(index);
    item.setText(new String[] {row.file(), row.folder(), row.result()});
    item.setForeground(row.failed() ? table.getDisplay().getSystemColor(SWT.COLOR_DARK_RED) : null);
  }

  /** Opens the window over its parent. */
  public void open() {
    refresh();
    Point size = shell.computeSize(820, 480);
    Rectangle owner = shell.getParent().getBounds();
    shell.setBounds(
        owner.x + Math.max(0, (owner.width - size.x) / 2),
        owner.y + Math.max(0, (owner.height - size.y) / 2),
        size.x,
        size.y);
    shell.open();
  }

  /** Reads the report again. */
  public void refresh() {
    if (shell.isDisposed()) {
      return;
    }
    summary.setText(input.summary().replace("&", "&&"));
    table.setItemCount(input.rowCount());
    table.clearAll();
    stop.setEnabled(input.running());
    restore.setEnabled(input.canRestore());
    shell.layout(new Control[] {summary});
  }

  /**
   * Returns the report shown.
   *
   * @return the report
   */
  public ReportInput input() {
    return input;
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
   * Returns a row's cells as shown, for tests.
   *
   * @param row the row
   * @return file, folder and result joined by tabs
   */
  public String text(int row) {
    TableItem item = table.getItem(row);
    return item.getText(0) + "\t" + item.getText(1) + "\t" + item.getText(2);
  }

  /**
   * Presses a button.
   *
   * @param label the button's label
   */
  public void press(UiText label) {
    if (label == UiText.REPLACE_STOP) {
      input.cancel();
    } else if (label == UiText.REPLACE_RESTORE) {
      input.restore();
    } else if (label == UiText.BUTTON_CLOSE) {
      shell.dispose();
      return;
    }
    refresh();
  }
}
