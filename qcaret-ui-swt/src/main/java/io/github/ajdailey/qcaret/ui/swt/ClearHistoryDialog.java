package io.github.ajdailey.qcaret.ui.swt;

import io.github.ajdailey.qcaret.app.i18n.UiText;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;

/**
 * Clear History or Cancel for the saved search histories; modal, opened without a nested event
 * loop. Cancel is the default button, and closing the dialog any other way declines.
 */
final class ClearHistoryDialog {
  private final Shell shell;
  private final Button clear;

  ClearHistoryDialog(Shell parent, final Runnable action) {
    shell = Dialogs.shell(parent, UiText.PREFERENCES_CLEAR_HISTORY_TITLE.text(), true);
    Label label = Dialogs.label(shell, UiText.PREFERENCES_CLEAR_HISTORY_MESSAGE.text());
    ((GridData) label.getLayoutData()).widthHint = 420;
    Composite row = Dialogs.buttonRow(shell, 1);
    clear =
        Dialogs.button(
            row,
            UiText.BUTTON_CLEAR_HISTORY.text(),
            new Runnable() {
              @Override
              public void run() {
                shell.dispose();
                action.run();
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

  /** Presses Clear History, for tests. */
  void clear() {
    clear.notifyListeners(SWT.Selection, new Event());
  }

  /** Presses Cancel, for tests. */
  void cancel() {
    shell.getDefaultButton().notifyListeners(SWT.Selection, new Event());
  }
}
