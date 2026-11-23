package io.github.ajdailey.qcaret.ui.swt;

import io.github.ajdailey.qcaret.app.i18n.UiText;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

/**
 * Asks before a Markdown preview link opens a web address in the system browser; modal, opened
 * without a nested event loop. The full address shows in a read-only field. Cancel is the default
 * and has the focus, so Enter and Esc both open nothing.
 */
final class OpenLinkDialog {
  private final Shell shell;
  private final Text address;
  private final Button open;
  private final Button cancel;

  OpenLinkDialog(Shell parent, String url, final Runnable confirmed) {
    shell = Dialogs.shell(parent, UiText.OPEN_LINK_TITLE.text(), true);
    Dialogs.label(shell, UiText.OPEN_LINK_MESSAGE.text());
    address = new Text(shell, SWT.READ_ONLY | SWT.WRAP | SWT.MULTI);
    address.setText(url);
    GridData data = new GridData(SWT.FILL, SWT.CENTER, true, false);
    data.widthHint = 460;
    address.setLayoutData(data);
    Composite row = Dialogs.buttonRow(shell, 1);
    open =
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

  Text address() {
    return address;
  }

  Button openButton() {
    return open;
  }

  Button cancel() {
    return cancel;
  }
}
