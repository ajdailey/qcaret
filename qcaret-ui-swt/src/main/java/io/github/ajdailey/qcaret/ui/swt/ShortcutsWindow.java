package io.github.ajdailey.qcaret.ui.swt;

import io.github.ajdailey.qcaret.app.command.CommandGroup;
import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.command.Keymap;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.view.ClipboardService;
import java.util.Locale;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;

/**
 * Help > Keyboard Shortcuts: a modeless table of every bound command, grouped by menu, from the
 * {@link Keymap}, with a filter field and Copy (plain text of the filtered rows, as {@link
 * Keymap#reference(String)} writes them).
 */
final class ShortcutsWindow {
  private final Shell shell;
  private final Keymap keymap;
  private final Table table;
  private final Text filter;

  ShortcutsWindow(Shell parent, Keymap keymap, final ClipboardService clipboard) {
    this.keymap = keymap;
    shell = Dialogs.shell(parent, UiText.SHORTCUTS_TITLE.text(), false);
    filter = new Text(shell, SWT.SEARCH | SWT.ICON_CANCEL | SWT.BORDER);
    filter.setMessage(UiText.SHORTCUTS_FILTER.text());
    filter.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    table = new Table(shell, SWT.BORDER | SWT.FULL_SELECTION | SWT.V_SCROLL);
    table.setHeaderVisible(true);
    table.setLinesVisible(true);
    table.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
    for (UiText title :
        new UiText[] {UiText.SHORTCUTS_GROUP, UiText.SHORTCUTS_COMMAND, UiText.SHORTCUTS_KEYS}) {
      new TableColumn(table, SWT.LEFT).setText(title.text());
    }
    filter.addListener(
        SWT.Modify,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            fill();
          }
        });
    Composite buttons = Dialogs.buttonRow(shell, 1);
    Dialogs.button(
        buttons,
        UiText.BUTTON_COPY.text(),
        new Runnable() {
          @Override
          public void run() {
            clipboard.setText(ShortcutsWindow.this.keymap.reference(filter.getText()));
          }
        });
    Button close = Dialogs.button(buttons, UiText.BUTTON_CLOSE.text(), Dialogs.closer(shell));
    shell.setDefaultButton(close);
    fill();
    for (TableColumn column : table.getColumns()) {
      column.pack();
    }
  }

  private void fill() {
    table.removeAll();
    String needle = filter.getText().toLowerCase(Locale.ROOT);
    for (CommandGroup group : CommandGroup.values()) {
      for (CommandId command : CommandId.values()) {
        String keys = keymap.keysText(command);
        if (command.group() != group || keys.isEmpty()) {
          continue;
        }
        String[] row = {group.title(), command.title(), keys};
        if (String.join(" ", row).toLowerCase(Locale.ROOT).contains(needle)) {
          new TableItem(table, SWT.NONE).setText(row);
        }
      }
    }
  }

  void open() {
    Dialogs.open(shell, 560, 520);
  }

  Shell shell() {
    return shell;
  }

  Table table() {
    return table;
  }

  Text filter() {
    return filter;
  }
}
