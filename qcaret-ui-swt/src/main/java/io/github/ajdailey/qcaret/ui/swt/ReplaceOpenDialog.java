package io.github.ajdailey.qcaret.ui.swt;

import io.github.ajdailey.qcaret.app.i18n.UiText;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;

/**
 * Asks whether Save As may replace a file another tab holds with unsaved changes, closing that tab
 * without saving; modal, opened without a nested event loop. Cancel is the default.
 */
final class ReplaceOpenDialog {
  private final Shell shell;

  ReplaceOpenDialog(Shell parent, String name, final Runnable replace) {
    shell = Dialogs.shell(parent, UiText.SAVE_AS_OPEN_TITLE.text(), true);
    Label label = Dialogs.label(shell, UiText.SAVE_AS_OPEN_MESSAGE.format(name.replace("&", "&&")));
    ((GridData) label.getLayoutData()).widthHint = 420;
    Composite row = Dialogs.buttonRow(shell, 1);
    Dialogs.button(
        row,
        UiText.BUTTON_REPLACE_FILE.text(),
        new Runnable() {
          @Override
          public void run() {
            shell.dispose();
            replace.run();
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
