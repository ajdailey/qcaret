package io.github.ajdailey.qcaret.ui.swt;

import io.github.ajdailey.qcaret.app.i18n.UiText;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.DirectoryDialog;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;

/**
 * The Preferences row for {@code workspace.default}: a read-only field, Choose, which opens a
 * folder chooser, and Clear, which empties the field.
 */
final class WorkspaceDefaultRow {
  private final Text folder;
  private final Button choose;
  private final Button clear;

  /** Adds the label and the row to a two-column page. */
  WorkspaceDefaultRow(Composite page) {
    new Label(page, SWT.NONE).setText(UiText.PREFERENCES_WORKSPACE_DEFAULT.text());
    Composite row = new Composite(page, SWT.NONE);
    GridLayout layout = new GridLayout(3, false);
    layout.marginWidth = 0;
    layout.marginHeight = 0;
    row.setLayout(layout);
    row.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    folder = new Text(row, SWT.SINGLE | SWT.BORDER | SWT.READ_ONLY);
    folder.setMessage(UiText.PREFERENCES_WORKSPACE_DEFAULT_NONE.text());
    GridData data = new GridData(SWT.FILL, SWT.CENTER, true, false);
    data.widthHint = 200;
    folder.setLayoutData(data);
    choose =
        Dialogs.button(
            row,
            UiText.BUTTON_CHOOSE_FOLDER.text(),
            new Runnable() {
              @Override
              public void run() {
                choose();
              }
            });
    clear =
        Dialogs.button(
            row,
            UiText.BUTTON_CLEAR_FOLDER.text(),
            new Runnable() {
              @Override
              public void run() {
                folder.setText("");
              }
            });
  }

  private void choose() {
    DirectoryDialog chooser = new DirectoryDialog(folder.getShell());
    if (!folder.getText().isEmpty()) {
      chooser.setFilterPath(folder.getText());
    }
    String chosen = chooser.open();
    if (chosen != null && !folder.isDisposed()) {
      folder.setText(chosen);
    }
  }

  /** Shows a folder, empty for none. */
  void show(String value) {
    folder.setText(value);
  }

  /** Returns the shown folder, empty for none. */
  String value() {
    return folder.getText();
  }

  Text field() {
    return folder;
  }

  Button chooseButton() {
    return choose;
  }

  Button clearButton() {
    return clear;
  }
}
