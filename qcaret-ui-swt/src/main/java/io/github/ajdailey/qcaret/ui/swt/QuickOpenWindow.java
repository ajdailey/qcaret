package io.github.ajdailey.qcaret.ui.swt;

import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.view.QuickOpenInput;
import io.github.ajdailey.qcaret.app.view.QuickOpenRow;
import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import java.util.List;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;

/**
 * File > Quick Open and Window > Switch Document: a modal dialog with a filter field over a table
 * of names and paths and a summary line; an open document's row is in bold. Each change of the
 * field calls {@link QuickOpenInput#filter}; the table refills then and on each {@link #changed},
 * selecting its first row. Up, Down, Page Up and Page Down in the field move the selection; Enter
 * or a double click closes the dialog and passes the row to {@link QuickOpenInput#choose} on the
 * next UI turn. Esc closes the dialog; closing it calls {@link QuickOpenInput#close}.
 */
final class QuickOpenWindow {
  private final Shell shell;
  private final QuickOpenInput input;
  private final UiDispatcher ui;
  private final Text filter;
  private final Table table;
  private final Label summary;
  private final Font bold;

  QuickOpenWindow(Shell parent, final QuickOpenInput input, UiDispatcher ui) {
    this.input = input;
    this.ui = ui;
    shell = Dialogs.shell(parent, input.title(), true);
    filter = new Text(shell, SWT.SEARCH | SWT.BORDER);
    filter.setMessage(UiText.QUICK_OPEN_FILTER.text());
    filter.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    table = new Table(shell, SWT.BORDER | SWT.SINGLE | SWT.FULL_SELECTION | SWT.V_SCROLL);
    table.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
    TableColumn name = new TableColumn(table, SWT.LEFT);
    name.setText(UiText.QUICK_OPEN_NAME.text());
    name.setWidth(200);
    TableColumn path = new TableColumn(table, SWT.LEFT);
    path.setText(UiText.QUICK_OPEN_PATH.text());
    path.setWidth(420);
    table.setHeaderVisible(true);
    FontData[] data = table.getFont().getFontData();
    for (FontData each : data) {
      each.setStyle(SWT.BOLD);
    }
    bold = new Font(shell.getDisplay(), data);
    summary = new Label(shell, SWT.NONE);
    summary.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    filter.addListener(
        SWT.Modify,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            input.filter(filter.getText());
            fill();
          }
        });
    filter.addListener(
        SWT.KeyDown,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            int step =
                switch (event.keyCode) {
                  case SWT.ARROW_UP -> -1;
                  case SWT.ARROW_DOWN -> 1;
                  case SWT.PAGE_UP -> -CommandPaletteWindow.PAGE;
                  case SWT.PAGE_DOWN -> CommandPaletteWindow.PAGE;
                  default -> 0;
                };
            if (step != 0 && table.getItemCount() > 0) {
              int at = Math.max(0, table.getSelectionIndex() + step);
              table.setSelection(Math.min(table.getItemCount() - 1, at));
              table.showSelection();
              event.doit = false;
            }
          }
        });
    Listener choose =
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            choose();
          }
        };
    filter.addListener(SWT.DefaultSelection, choose);
    table.addListener(SWT.DefaultSelection, choose);
    shell.addListener(
        SWT.Dispose,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            input.close();
            bold.dispose();
          }
        });
    fill();
  }

  /** Refills the table and the summary from the input and selects the first row. */
  void changed() {
    if (!shell.isDisposed()) {
      fill();
    }
  }

  private void fill() {
    List<QuickOpenRow> rows = input.rows();
    table.setRedraw(false);
    try {
      table.removeAll();
      for (QuickOpenRow row : rows) {
        TableItem item = new TableItem(table, SWT.NONE);
        item.setText(new String[] {row.name(), row.detail()});
        if (row.open()) {
          item.setFont(bold);
        }
      }
      if (!rows.isEmpty()) {
        table.setSelection(0);
      }
    } finally {
      table.setRedraw(true);
    }
    summary.setText(input.summary());
  }

  private void choose() {
    final int index = table.getSelectionIndex();
    if (index < 0) {
      return;
    }
    shell.dispose();
    ui.asyncExec(
        new Runnable() {
          @Override
          public void run() {
            input.choose(index);
          }
        });
  }

  void open() {
    Dialogs.open(shell, 640, 440);
    filter.setFocus();
  }

  Shell shell() {
    return shell;
  }

  Text filter() {
    return filter;
  }

  Table table() {
    return table;
  }

  String summary() {
    return summary.getText();
  }
}
