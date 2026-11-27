package io.github.ajdailey.qcaret.ui.swt;

import io.github.ajdailey.qcaret.app.command.Keymap;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.view.MacroManagerDisplay;
import io.github.ajdailey.qcaret.app.view.MacroManagerInput;
import java.util.List;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;

/**
 * Macros > Manage Macros: a modal list of the macros folder's files with their keys and why a file
 * is unreadable, and the selected macro's name and key fields. Run closes the dialog and runs the
 * macro; Rename and Set Key apply the fields; Delete asks first ({@link DeleteDialog}). A refused
 * name or key, or a failed file operation, shows under the fields. Opens without a nested event
 * loop.
 */
final class ManageMacrosDialog implements MacroManagerDisplay {
  private final Shell shell;
  private final MacroManagerInput input;
  private final Keymap keymap;
  private final Table table;
  private final Text name;
  private final KeyField key;
  private final Label message;
  private final Button run;
  private final Button rename;
  private final Button setKey;
  private final Button delete;
  private List<MacroManagerInput.Row> rows = List.of();

  ManageMacrosDialog(Shell parent, Keymap keymap, MacroManagerInput input) {
    this.input = input;
    this.keymap = keymap;
    shell = Dialogs.shell(parent, UiText.MANAGE_MACROS_TITLE.text(), true);
    table = new Table(shell, SWT.BORDER | SWT.FULL_SELECTION | SWT.V_SCROLL | SWT.SINGLE);
    table.setHeaderVisible(true);
    Dialogs.gridLines(table);
    GridData tableData = new GridData(SWT.FILL, SWT.FILL, true, true);
    tableData.heightHint = 200;
    table.setLayoutData(tableData);
    for (UiText title :
        new UiText[] {
          UiText.MANAGE_MACROS_NAME, UiText.MANAGE_MACROS_KEY, UiText.MANAGE_MACROS_PROBLEM
        }) {
      new TableColumn(table, SWT.LEFT).setText(title.text());
    }
    Composite fields = new Composite(shell, SWT.NONE);
    GridLayout layout = new GridLayout(4, false);
    layout.marginWidth = 0;
    layout.marginHeight = 0;
    fields.setLayout(layout);
    fields.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    new Label(fields, SWT.NONE).setText(UiText.SAVE_MACRO_NAME.text());
    name = new Text(fields, SWT.SINGLE | SWT.BORDER);
    name.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
    rename =
        Dialogs.button(
            fields,
            UiText.BUTTON_RENAME.text(),
            new Runnable() {
              @Override
              public void run() {
                renameSelected();
              }
            });
    new Label(fields, SWT.NONE).setText(UiText.SAVE_MACRO_KEY.text());
    key =
        new KeyField(
            fields,
            keymap,
            new Runnable() {
              @Override
              public void run() {
                // The key applies on Set Key.
              }
            });
    Dialogs.button(
        fields,
        UiText.BUTTON_CLEAR_KEY.text(),
        new Runnable() {
          @Override
          public void run() {
            key.set(null);
          }
        });
    setKey =
        Dialogs.button(
            fields,
            UiText.BUTTON_SET_KEY.text(),
            new Runnable() {
              @Override
              public void run() {
                setSelectedKey();
              }
            });
    message = Dialogs.label(shell, "");
    Composite buttons = Dialogs.buttonRow(shell, 1);
    run =
        Dialogs.button(
            buttons,
            UiText.BUTTON_RUN.text(),
            new Runnable() {
              @Override
              public void run() {
                runSelected();
              }
            });
    delete =
        Dialogs.button(
            buttons,
            UiText.BUTTON_DELETE.text(),
            new Runnable() {
              @Override
              public void run() {
                deleteSelected();
              }
            });
    Button close = Dialogs.button(buttons, UiText.BUTTON_CLOSE.text(), Dialogs.closer(shell));
    shell.setDefaultButton(close);
    table.addListener(
        SWT.Selection,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            showSelected();
          }
        });
    table.addListener(
        SWT.DefaultSelection,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            runSelected();
          }
        });
    fill(null);
  }

  @Override
  public void rowsChanged() {
    if (!shell.isDisposed()) {
      MacroManagerInput.Row selected = selected();
      fill(selected == null ? null : selected.name());
    }
  }

  @Override
  public void showMessage(String text) {
    if (!shell.isDisposed()) {
      setMessage(text);
    }
  }

  private void setMessage(String text) {
    message.setText(text == null ? "" : text.replace("&", "&&"));
    shell.layout(true, true);
  }

  /** Lists the rows again and selects a name, or the first row when it is gone. */
  private void fill(String keep) {
    rows = input.rows();
    table.removeAll();
    int select = rows.isEmpty() ? -1 : 0;
    for (int i = 0; i < rows.size(); i++) {
      MacroManagerInput.Row row = rows.get(i);
      new TableItem(table, SWT.NONE)
          .setText(
              new String[] {
                row.name(), row.key() == null ? "" : keymap.keyText(row.key()), row.problem()
              });
      if (row.name().equals(keep)) {
        select = i;
      }
    }
    for (TableColumn column : table.getColumns()) {
      column.pack();
    }
    if (select >= 0) {
      table.setSelection(select);
    }
    showSelected();
  }

  private MacroManagerInput.Row selected() {
    int index = table.getSelectionIndex();
    return index < 0 || index >= rows.size() ? null : rows.get(index);
  }

  private void showSelected() {
    MacroManagerInput.Row row = selected();
    boolean readable = row != null && row.problem().isEmpty();
    name.setText(row == null ? "" : row.name());
    key.set(row == null ? null : row.key());
    name.setEnabled(row != null);
    rename.setEnabled(row != null);
    delete.setEnabled(row != null);
    run.setEnabled(readable);
    setKey.setEnabled(readable);
    key.text().setEnabled(readable);
  }

  void renameSelected() {
    MacroManagerInput.Row row = selected();
    if (row != null) {
      setMessage(input.rename(row.name(), name.getText()));
    }
  }

  void setSelectedKey() {
    MacroManagerInput.Row row = selected();
    if (row != null) {
      setMessage(input.setKey(row.name(), key.stroke()));
    }
  }

  void runSelected() {
    MacroManagerInput.Row row = selected();
    if (row != null && row.problem().isEmpty()) {
      shell.dispose();
      input.run(row.name());
    }
  }

  void deleteSelected() {
    final MacroManagerInput.Row row = selected();
    if (row != null) {
      new DeleteDialog(
              shell,
              UiText.MACRO_DELETE_ASK.format(row.name()),
              new Runnable() {
                @Override
                public void run() {
                  input.delete(row.name());
                }
              })
          .open();
    }
  }

  void open() {
    Dialogs.open(shell, 520, SWT.DEFAULT);
    table.setFocus();
  }

  Shell shell() {
    return shell;
  }

  Table table() {
    return table;
  }

  Text name() {
    return name;
  }

  KeyField key() {
    return key;
  }

  Label message() {
    return message;
  }

  Button run() {
    return run;
  }

  Button delete() {
    return delete;
  }
}
