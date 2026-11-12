package io.github.ajdailey.qcaret.ui.swt;

import io.github.ajdailey.qcaret.app.i18n.UiText;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;

/**
 * Asks before the workspace browser opens many files in their default applications; modal, opened
 * without a nested event loop. Cancel is the default and has the focus.
 */
final class OpenManyDialog {
  private final Shell shell;
  private final Button openButton;
  private final Button cancel;

  OpenManyDialog(Shell parent, int count, final Runnable confirmed) {
    shell = Dialogs.shell(parent, UiText.OPEN_MANY_TITLE.text(), true);
    Label label = Dialogs.label(shell, UiText.OPEN_MANY_MESSAGE.format(count));
    ((GridData) label.getLayoutData()).widthHint = 420;
    Composite row = Dialogs.buttonRow(shell, 1);
    openButton =
        Dialogs.button(
            row,
            UiText.BUTTON_OPEN.text(),
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

  Button openButton() {
    return openButton;
  }

  Button cancel() {
    return cancel;
  }
}
