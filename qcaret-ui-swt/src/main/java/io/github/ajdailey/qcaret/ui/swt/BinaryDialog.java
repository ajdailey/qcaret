package io.github.ajdailey.qcaret.ui.swt;

import io.github.ajdailey.qcaret.app.i18n.UiText;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;

/**
 * Open Read-Only or Cancel for a binary file; modal, opened without a nested event loop. Cancel is
 * the default button, and closing the dialog any other way declines.
 */
final class BinaryDialog {
  private final Shell shell;

  BinaryDialog(Shell parent, String name, final Runnable open) {
    shell = Dialogs.shell(parent, UiText.BINARY_TITLE.text(), true);
    Label label = Dialogs.label(shell, UiText.BINARY_MESSAGE.format(name.replace("&", "&&")));
    ((GridData) label.getLayoutData()).widthHint = 420;
    Composite row = Dialogs.buttonRow(shell, 1);
    Dialogs.button(
        row,
        UiText.BUTTON_OPEN_READ_ONLY.text(),
        new Runnable() {
          @Override
          public void run() {
            // Close first, so a notice the open shows is not stacked under this dialog.
            shell.dispose();
            open.run();
          }
        });
    Button cancel = Dialogs.button(row, UiText.BUTTON_CANCEL.text(), Dialogs.closer(shell));
    shell.setDefaultButton(cancel);
  }

  void open() {
    Dialogs.open(shell, SWT.DEFAULT, SWT.DEFAULT);
  }

  Shell shell() {
    return shell;
  }
}
