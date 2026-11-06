package io.github.ajdailey.qcaret.ui.swt;

import io.github.ajdailey.qcaret.app.i18n.UiText;
import java.util.function.IntConsumer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Spinner;

/**
 * Edit > Tab Width > Other: a modal prompt for a width. OK passes it to the answer and closes;
 * Cancel and Esc close without an answer. Opens without a nested event loop.
 */
final class TabWidthDialog {
  private final Shell shell;
  private final Spinner field;

  TabWidthDialog(Shell parent, int current, int max, final IntConsumer answer) {
    shell = Dialogs.shell(parent, UiText.TAB_WIDTH_TITLE.text(), true);
    Dialogs.label(shell, UiText.TAB_WIDTH_MESSAGE.format(max));
    field = new Spinner(shell, SWT.BORDER);
    field.setValues(current, 1, max, 0, 1, 4);
    field.setLayoutData(new GridData(SWT.BEGINNING, SWT.CENTER, false, false));
    Composite buttons = Dialogs.buttonRow(shell, 1);
    Button ok =
        Dialogs.button(
            buttons,
            UiText.BUTTON_OK.text(),
            new Runnable() {
              @Override
              public void run() {
                int value = field.getSelection();
                shell.dispose();
                answer.accept(value);
              }
            });
    Dialogs.button(buttons, UiText.BUTTON_CANCEL.text(), Dialogs.closer(shell));
    shell.setDefaultButton(ok);
  }

  void open() {
    Dialogs.open(shell, 320, SWT.DEFAULT);
    field.setFocus();
  }

  Shell shell() {
    return shell;
  }

  Spinner field() {
    return field;
  }
}
