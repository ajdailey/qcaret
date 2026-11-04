package io.github.ajdailey.qcaret.ui.swt;

import io.github.ajdailey.qcaret.app.i18n.UiText;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;

/** A one-line error or message with an OK button; modal, opened without a nested event loop. */
final class ErrorDialog {
  private final Shell shell;

  ErrorDialog(Shell parent, String title, String message) {
    shell = Dialogs.shell(parent, title, true);
    Label label = Dialogs.label(shell, message);
    GridData data = (GridData) label.getLayoutData();
    data.widthHint = 420;
    Button ok =
        Dialogs.button(Dialogs.buttonRow(shell, 1), UiText.BUTTON_OK.text(), Dialogs.closer(shell));
    shell.setDefaultButton(ok);
  }

  void open() {
    Dialogs.open(shell, SWT.DEFAULT, SWT.DEFAULT);
  }

  Shell shell() {
    return shell;
  }
}
