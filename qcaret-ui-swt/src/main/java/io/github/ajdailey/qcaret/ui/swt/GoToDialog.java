package io.github.ajdailey.qcaret.ui.swt;

import io.github.ajdailey.qcaret.app.i18n.UiText;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

/**
 * Search > Go to Line: a modal prompt for a line or {@code line:column}. OK is enabled while the
 * validator accepts the text; it passes the text to the answer and closes. Cancel and Esc close
 * without an answer. Opens without a nested event loop.
 */
final class GoToDialog {
  private final Shell shell;
  private final Text field;
  private final Button ok;

  GoToDialog(
      Shell parent, int lines, final Predicate<String> valid, final Consumer<String> answer) {
    shell = Dialogs.shell(parent, UiText.GO_TO_TITLE.text(), true);
    Dialogs.label(shell, UiText.GO_TO_MESSAGE.format(lines));
    field = new Text(shell, SWT.SINGLE | SWT.BORDER);
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
                if (valid.test(value)) {
                  shell.dispose();
                  answer.accept(value);
                }
              }
            });
    Dialogs.button(buttons, UiText.BUTTON_CANCEL.text(), Dialogs.closer(shell));
    shell.setDefaultButton(ok);
    ok.setEnabled(false);
    field.addListener(
        SWT.Modify,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            ok.setEnabled(valid.test(field.getText()));
          }
        });
  }

  void open() {
    Dialogs.open(shell, 320, SWT.DEFAULT);
    field.setFocus();
  }

  Shell shell() {
    return shell;
  }

  Text field() {
    return field;
  }
}
