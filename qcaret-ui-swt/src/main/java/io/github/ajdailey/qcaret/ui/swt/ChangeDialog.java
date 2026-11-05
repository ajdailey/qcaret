package io.github.ajdailey.qcaret.ui.swt;

import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.view.ChangeChoice;
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
 * Asks what to do about a file another program changed: Keep Mine, Reload or Compare, or, when a
 * save found the change, Overwrite, Reload, Compare or Cancel. Modeless, so the comparison window
 * it opens takes input; Compare leaves it open, every other button closes it. Closing it any other
 * way answers Keep Mine, or Cancel for a save.
 */
final class ChangeDialog {
  private final Shell shell;
  private final Consumer<ChangeChoice> answer;
  private boolean answered;

  ChangeDialog(Shell parent, String name, boolean saving, Consumer<ChangeChoice> answer) {
    this.answer = answer;
    shell = Dialogs.shell(parent, UiText.CHANGE_TITLE.text(), SWT.DIALOG_TRIM);
    String escaped = name.replace("&", "&&");
    Label label =
        Dialogs.label(
            shell,
            saving
                ? UiText.CHANGE_SAVE_MESSAGE.format(escaped)
                : UiText.CHANGE_MESSAGE.format(escaped));
    ((GridData) label.getLayoutData()).widthHint = 420;
    Composite row = Dialogs.buttonRow(shell, 1);
    Button first;
    if (saving) {
      first = Dialogs.button(row, UiText.BUTTON_OVERWRITE.text(), choose(ChangeChoice.OVERWRITE));
    } else {
      first = Dialogs.button(row, UiText.BUTTON_KEEP_MINE.text(), choose(ChangeChoice.KEEP));
    }
    Dialogs.button(row, UiText.BUTTON_RELOAD.text(), choose(ChangeChoice.RELOAD));
    Dialogs.button(
        row,
        UiText.BUTTON_COMPARE.text(),
        new Runnable() {
          @Override
          public void run() {
            if (!answered) {
              answer.accept(ChangeChoice.COMPARE);
            }
          }
        });
    if (saving) {
      Dialogs.button(row, UiText.BUTTON_CANCEL.text(), choose(ChangeChoice.CANCEL));
    }
    shell.setDefaultButton(first);
    final ChangeChoice closed = saving ? ChangeChoice.CANCEL : ChangeChoice.KEEP;
    shell.addListener(
        SWT.Dispose,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            if (!answered) {
              answered = true;
              answer.accept(closed);
            }
          }
        });
  }

  private Runnable choose(final ChangeChoice choice) {
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

  void open() {
    Dialogs.open(shell, SWT.DEFAULT, SWT.DEFAULT);
  }

  Shell shell() {
    return shell;
  }

  /**
   * Presses a button, for tests.
   *
   * @param choice the button's choice
   */
  void press(ChangeChoice choice) {
    if (choice == ChangeChoice.COMPARE) {
      answer.accept(choice);
    } else {
      choose(choice).run();
    }
  }
}
