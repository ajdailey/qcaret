package io.github.ajdailey.qcaret.ui.swt;

import io.github.ajdailey.qcaret.app.i18n.UiText;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;

/**
 * A one-line error or message with an OK button, and optionally an action button; modal, opened
 * without a nested event loop. An {@code &} in the message shows as itself.
 */
final class ErrorDialog {
  private final Shell shell;

  ErrorDialog(Shell parent, String title, String message) {
    this(parent, title, message, null, null);
  }

  /**
   * Creates the dialog with a button before OK that closes it and runs an action.
   *
   * @param parent the owner
   * @param title the title
   * @param message the message
   * @param label the action button's label, or null for OK alone
   * @param action runs after the dialog closed
   */
  ErrorDialog(Shell parent, String title, String message, String label, final Runnable action) {
    shell = Dialogs.shell(parent, title, true);
    Label text = Dialogs.label(shell, message.replace("&", "&&"));
    GridData data = (GridData) text.getLayoutData();
    data.widthHint = 420;
    Composite row = Dialogs.buttonRow(shell, 1);
    if (label != null) {
      Dialogs.button(
          row,
          label,
          new Runnable() {
            @Override
            public void run() {
              shell.dispose();
              action.run();
            }
          });
    }
    Button ok = Dialogs.button(row, UiText.BUTTON_OK.text(), Dialogs.closer(shell));
    shell.setDefaultButton(ok);
  }

  void open() {
    Dialogs.open(shell, SWT.DEFAULT, SWT.DEFAULT);
  }

  Shell shell() {
    return shell;
  }
}
