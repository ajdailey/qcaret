package io.github.ajdailey.qcaret.ui.swt;

import io.github.ajdailey.qcaret.app.i18n.UiText;
import java.util.List;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;

/**
 * Asks whether a quit goes on while Save a Copy As writes run, naming each file; modal, opened
 * without a nested event loop. Wait is the default; Esc and the close button answer Wait. {@link
 * #close} disposes it without an answer.
 */
final class UnfinishedCopiesDialog {
  private final Shell shell;
  private boolean answered;

  UnfinishedCopiesDialog(
      Shell parent, List<String> targets, final Runnable quit, final Runnable wait) {
    shell = Dialogs.shell(parent, UiText.SAVE_COPY_UNFINISHED_TITLE.text(), true);
    Label label = Dialogs.label(shell, UiText.SAVE_COPY_UNFINISHED.text());
    ((GridData) label.getLayoutData()).widthHint = 420;
    StringBuilder files = new StringBuilder();
    for (String target : targets) {
      files.append(files.length() == 0 ? "" : "\n").append(target.replace("&", "&&"));
    }
    Dialogs.label(shell, files.toString());
    Composite row = Dialogs.buttonRow(shell, 1);
    Dialogs.button(
        row,
        UiText.BUTTON_QUIT.text(),
        new Runnable() {
          @Override
          public void run() {
            answered = true;
            shell.dispose();
            quit.run();
          }
        });
    Button waitButton = Dialogs.button(row, UiText.BUTTON_WAIT.text(), Dialogs.closer(shell));
    shell.setDefaultButton(waitButton);
    shell.addListener(
        SWT.Dispose,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            if (!answered) {
              answered = true;
              wait.run();
            }
          }
        });
  }

  void open() {
    Dialogs.open(shell, SWT.DEFAULT, SWT.DEFAULT);
  }

  /** Disposes the dialog without running either answer. */
  void close() {
    answered = true;
    if (!shell.isDisposed()) {
      shell.dispose();
    }
  }

  Shell shell() {
    return shell;
  }
}
