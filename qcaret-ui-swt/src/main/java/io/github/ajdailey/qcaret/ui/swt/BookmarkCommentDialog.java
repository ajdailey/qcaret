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
 * Edit > Edit Bookmark Comment: a modal one-line field holding the current comment, limited to the
 * comment length. OK passes the text to the answer; Cancel, Esc and closing pass null. The answer
 * runs once. Opens without a nested event loop.
 */
final class BookmarkCommentDialog {
  private final Shell shell;
  private final Text field;
  private final Button ok;
  private boolean answered;

  BookmarkCommentDialog(
      Shell parent, int line, String current, int maxLength, final Consumer<String> answer) {
    shell = Dialogs.shell(parent, UiText.BOOKMARK_COMMENT_TITLE.text(), true);
    Dialogs.label(shell, UiText.BOOKMARK_COMMENT_MESSAGE.format(line + 1));
    field = new Text(shell, SWT.SINGLE | SWT.BORDER);
    field.setTextLimit(maxLength);
    field.setText(current);
    field.selectAll();
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
    Dialogs.open(shell, 480, SWT.DEFAULT);
    field.setFocus();
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
