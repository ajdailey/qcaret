package io.github.ajdailey.qcaret.ui.swt;

import io.github.ajdailey.qcaret.app.i18n.UiText;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;

/**
 * Asks before the workspace browser deletes a file or folder permanently; modal, opened without a
 * nested event loop. Cancel is the default and has the focus, so Enter and Esc both keep the files.
 */
final class DeleteDialog {
  private final Shell shell;
  private final Button delete;
  private final Button cancel;

  DeleteDialog(Shell parent, String message, final Runnable confirmed) {
    shell = Dialogs.shell(parent, UiText.DELETE_TITLE.text(), true);
    Label label = Dialogs.label(shell, message.replace("&", "&&"));
    ((GridData) label.getLayoutData()).widthHint = 460;
    Composite row = Dialogs.buttonRow(shell, 1);
    delete =
        Dialogs.button(
            row,
            UiText.BUTTON_DELETE.text(),
            new Runnable() {
              @Override
              public void run() {
                shell.dispose();
                confirmed.run();
              }
            });
    cancel = Dialogs.button(row, UiText.BUTTON_CANCEL.text(), Dialogs.closer(shell));
    shell.setDefaultButton(cancel);
  }

  void open() {
    Dialogs.open(shell, SWT.DEFAULT, SWT.DEFAULT);
    cancel.setFocus();
  }

  Shell shell() {
    return shell;
  }

  Button delete() {
    return delete;
  }

  Button cancel() {
    return cancel;
  }
}
