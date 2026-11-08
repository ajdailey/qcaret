package io.github.ajdailey.qcaret.ui.swt;

import io.github.ajdailey.qcaret.app.i18n.UiText;
import java.util.function.Consumer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

/**
 * The workspace browser's name field for New File, New Folder and Rename: modal, one line, with the
 * name selected up to a given end. OK passes the text to the answer; Cancel, Esc and closing pass
 * null. The answer runs once. Opens without a nested event loop.
 */
final class FileNameDialog {
  private final Shell shell;
  private final Text field;
  private final Button ok;
  private final int selectEnd;
  private boolean answered;

  FileNameDialog(
      Shell parent,
      String title,
      String message,
      String name,
      int selectEnd,
      final Consumer<String> answer) {
    this.selectEnd = selectEnd;
    shell = Dialogs.shell(parent, title, true);
    Dialogs.label(shell, message.replace("&", "&&"));
    field = new Text(shell, SWT.SINGLE | SWT.BORDER);
    field.setText(name);
    field.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    Composite buttons = Dialogs.buttonRow(shell, 1);
    ok =
        Dialogs.button(
            buttons,
            UiText.BUTTON_OK.text(),
            new Runnable() {
              @Override
              public void run() {
                String value = field.getText();
                answered = true;
                shell.dispose();
                answer.accept(value);
              }
            });
    Dialogs.button(buttons, UiText.BUTTON_CANCEL.text(), Dialogs.closer(shell));
    shell.setDefaultButton(ok);
    shell.addListener(
        SWT.Dispose,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            if (!answered) {
              answered = true;
              answer.accept(null);
            }
          }
        });
  }

  void open() {
    Dialogs.open(shell, 420, SWT.DEFAULT);
    field.setFocus();
    field.setSelection(0, selectEnd);
  }

  Shell shell() {
    return shell;
  }

  Text field() {
    return field;
  }

  Button ok() {
    return ok;
  }
}
