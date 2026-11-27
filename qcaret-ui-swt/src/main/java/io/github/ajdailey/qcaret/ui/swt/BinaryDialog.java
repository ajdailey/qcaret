package io.github.ajdailey.qcaret.ui.swt;

import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.view.BinaryChoice;
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
 * Open in Hex Viewer, Open as Text or Cancel for a binary file; modal, opened without a nested
 * event loop. Open in Hex Viewer is the default button. The answer arrives once: closing the dialog
 * any other way answers Cancel.
 */
final class BinaryDialog {
  private final Shell shell;
  private final Consumer<BinaryChoice> answer;
  private boolean answered;

  BinaryDialog(Shell parent, String name, Consumer<BinaryChoice> answer) {
    this.answer = answer;
    shell = Dialogs.shell(parent, UiText.BINARY_TITLE.text(), true);
    Label label = Dialogs.label(shell, UiText.BINARY_HEX_MESSAGE.format(name.replace("&", "&&")));
    ((GridData) label.getLayoutData()).widthHint = 420;
    Composite row = Dialogs.buttonRow(shell, 1);
    Button hex =
        Dialogs.button(
            row, UiText.BUTTON_OPEN_IN_HEX_VIEWER.text(), choose(BinaryChoice.HEX_VIEWER));
    Dialogs.button(row, UiText.BUTTON_OPEN_AS_TEXT.text(), choose(BinaryChoice.TEXT));
    Dialogs.button(row, UiText.BUTTON_CANCEL.text(), choose(BinaryChoice.CANCEL));
    shell.setDefaultButton(hex);
    shell.addListener(
        SWT.Dispose,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            answer(BinaryChoice.CANCEL);
          }
        });
  }

  private Runnable choose(final BinaryChoice choice) {
    return new Runnable() {
      @Override
      public void run() {
        // Close first, so a window or notice the answer opens is not stacked under this dialog.
        if (!answered) {
          answered = true;
          shell.dispose();
          answer.accept(choice);
        }
      }
    };
  }

  private void answer(BinaryChoice choice) {
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
