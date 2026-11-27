package io.github.ajdailey.qcaret.ui.swt;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.view.PaletteEntry;
import io.github.ajdailey.qcaret.app.view.PaletteInput;
import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import java.util.List;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;

/**
 * View > Command Palette: a modal dialog with a filter field over a table of commands, their menus
 * and keys, refilled through {@link PaletteInput#filter} on each change. Up, Down, Page Up and Page
 * Down in the field move the table's selection; Enter or a double click closes the dialog and runs
 * the selected row's command on the next UI turn, so the editor has its focus back first. A row
 * whose command is disabled is gray and does not run. Esc closes the dialog. A cell cut off shows
 * its full text as a tool tip.
 */
final class CommandPaletteWindow {
  /** Rows Page Up and Page Down move. */
  static final int PAGE = 10;

  private final Shell shell;
  private final PaletteInput input;
  private final UiDispatcher ui;
  private final Text filter;
  private final Table table;
  private List<PaletteEntry> entries = List.of();

  CommandPaletteWindow(Shell parent, PaletteInput input, UiDispatcher ui) {
    this.input = input;
    this.ui = ui;
    shell =
        Dialogs.shell(
            parent,
            UiText.PALETTE_TITLE.text(),
            SWT.DIALOG_TRIM | SWT.RESIZE | SWT.APPLICATION_MODAL);
    filter = new Text(shell, SWT.SEARCH | SWT.BORDER);
    filter.setMessage(UiText.PALETTE_FILTER.text());
    filter.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    table = new Table(shell, SWT.BORDER | SWT.SINGLE | SWT.FULL_SELECTION | SWT.V_SCROLL);
    table.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
    for (UiText title :
        new UiText[] {UiText.SHORTCUTS_COMMAND, UiText.SHORTCUTS_GROUP, UiText.SHORTCUTS_KEYS}) {
      new TableColumn(table, SWT.LEFT).setText(title.text());
    }
    table.setHeaderVisible(true);
    filter.addListener(
        SWT.Modify,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
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
                  case SWT.PAGE_UP -> -PAGE;
                  case SWT.PAGE_DOWN -> PAGE;
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
    fill();
    for (TableColumn column : table.getColumns()) {
      column.pack();
    }
    TableFit.cellTips(table);
  }

  /** Refills the table for the filter and selects its first row. */
  private void fill() {
    entries = input.filter(filter.getText());
    table.setRedraw(false);
    try {
      table.removeAll();
      Color gray = shell.getDisplay().getSystemColor(SWT.COLOR_WIDGET_DISABLED_FOREGROUND);
      for (PaletteEntry entry : entries) {
        TableItem item = new TableItem(table, SWT.NONE);
        item.setText(new String[] {entry.title(), entry.group(), entry.keys()});
        if (!entry.enabled()) {
          item.setForeground(gray);
        }
      }
      if (!entries.isEmpty()) {
        table.setSelection(0);
      }
    } finally {
      table.setRedraw(true);
    }
  }

  /** Closes the dialog and runs the selected row's command or saved macro. */
  private void choose() {
    int index = table.getSelectionIndex();
    if (index < 0 || index >= entries.size() || !entries.get(index).enabled()) {
      return;
    }
    final CommandId command = entries.get(index).command();
    final String macro = entries.get(index).macro();
    shell.dispose();
    ui.asyncExec(
        new Runnable() {
          @Override
          public void run() {
            if (macro != null) {
              input.runMacro(macro);
            } else {
              input.run(command);
            }
          }
        });
  }

  /** Opens the dialog at least 520 px wide and wide enough for every column, within the screen. */
  void open() {
    open(shell.getParent().getMonitor().getClientArea());
  }

  /**
   * Opens the dialog sized to fit an area.
   *
   * @param screen the area the dialog must fit in
   */
  void open(Rectangle screen) {
    Dialogs.open(shell, TableFit.dialogWidth(shell, table, 520, screen), 420, screen);
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
}
