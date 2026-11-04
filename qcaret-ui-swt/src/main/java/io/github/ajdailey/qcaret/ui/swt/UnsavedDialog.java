package io.github.ajdailey.qcaret.ui.swt;

import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.view.UnsavedChoice;
import java.util.function.Consumer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;

/**
 * Save, Don't Save or Cancel for a document with unsaved changes; modal, opened without a nested
 * event loop. The answer arrives once: closing the dialog any other way answers Cancel.
 */
final class UnsavedDialog {
  private final Shell shell;
  private final Consumer<UnsavedChoice> answer;
  private boolean answered;

  UnsavedDialog(Shell parent, String name, Consumer<UnsavedChoice> answer) {
    this.answer = answer;
    shell = Dialogs.shell(parent, UiText.UNSAVED_TITLE.text(), true);
    Label label = Dialogs.label(shell, UiText.UNSAVED_MESSAGE.format(name.replace("&", "&&")));
    ((GridData) label.getLayoutData()).widthHint = 380;
    Composite row = Dialogs.buttonRow(shell, 1);
    Button save = Dialogs.button(row, UiText.BUTTON_SAVE.text(), choose(UnsavedChoice.SAVE));
    Dialogs.button(row, UiText.BUTTON_DONT_SAVE.text(), choose(UnsavedChoice.DISCARD));
    Dialogs.button(row, UiText.BUTTON_CANCEL.text(), choose(UnsavedChoice.CANCEL));
    shell.setDefaultButton(save);
    shell.addListener(
        SWT.Dispose,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            answer(UnsavedChoice.CANCEL);
          }
        });
  }

  private Runnable choose(final UnsavedChoice choice) {
    return new Runnable() {
      @Override
      public void run() {
        // Close first, so a prompt the answer opens is not stacked under this one.
        if (!answered) {
          answered = true;
          shell.dispose();
          answer.accept(choice);
        }
      }
    };
  }

  private void answer(UnsavedChoice choice) {
    if (!answered) {
      answered = true;
      answer.accept(choice);
    }
  }

  void open() {
    Dialogs.open(shell, SWT.DEFAULT, SWT.DEFAULT);
  }

  Shell shell() {
    return shell;
  }
}
