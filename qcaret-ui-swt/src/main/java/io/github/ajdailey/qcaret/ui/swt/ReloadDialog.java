package io.github.ajdailey.qcaret.ui.swt;

import io.github.ajdailey.qcaret.app.i18n.UiText;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;

/**
 * Asks whether Reload from Disk may replace a document's unsaved changes; modal, opened without a
 * nested event loop. Cancel is the default.
 */
final class ReloadDialog {
  private final Shell shell;
  private boolean accepted;

  ReloadDialog(Shell parent, String name, final Runnable reload, final Runnable declined) {
    shell = Dialogs.shell(parent, UiText.RELOAD_TITLE.text(), true);
    Label label = Dialogs.label(shell, UiText.RELOAD_MESSAGE.format(name.replace("&", "&&")));
    ((GridData) label.getLayoutData()).widthHint = 420;
    Composite row = Dialogs.buttonRow(shell, 1);
    Dialogs.button(
        row,
        UiText.BUTTON_RELOAD.text(),
        new Runnable() {
          @Override
          public void run() {
            accepted = true;
            shell.dispose();
            reload.run();
          }
        });
    Button cancel = Dialogs.button(row, UiText.BUTTON_CANCEL.text(), Dialogs.closer(shell));
    shell.setDefaultButton(cancel);
    shell.addListener(
        SWT.Dispose,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            if (!accepted) {
              declined.run();
            }
          }
        });
  }

  void open() {
    Dialogs.open(shell, SWT.DEFAULT, SWT.DEFAULT);
  }

  Shell shell() {
    return shell;
  }
}
