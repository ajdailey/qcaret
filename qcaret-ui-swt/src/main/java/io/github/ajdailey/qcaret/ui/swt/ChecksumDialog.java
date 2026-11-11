package io.github.ajdailey.qcaret.ui.swt;

import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.view.ChecksumDisplay;
import io.github.ajdailey.qcaret.app.view.ChecksumInput;
import java.util.List;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

/**
 * Tools > Generate from Files and Tools > Verify Checksums: a modeless window with a note, the
 * files or list read, the lines in a read-only monospace field, a progress row with Cancel, a
 * status line, and Choose, Copy, Save As and Close. Esc cancels a run while the progress row shows
 * and closes the window otherwise; closing calls {@link ChecksumInput#closed}.
 */
final class ChecksumDialog implements ChecksumDisplay {
  /** A display for a disposed window: shows nothing. */
  static final ChecksumDisplay NONE =
      new ChecksumDisplay() {
        @Override
        public void clear(String source) {
          // Nothing shows.
        }

        @Override
        public void addLines(List<String> lines) {
          // Nothing shows.
        }

        @Override
        public void showProgress(String text, int percent) {
          // Nothing shows.
        }

        @Override
        public void showStatus(String text) {
          // Nothing shows.
        }
      };

  private final Shell shell;
  private final ChecksumInput input;
  private final Label source;
  private final Text lines;
  private final ProgressRow progress;
  private final Label status;

  ChecksumDialog(
      Shell parent, String title, String note, String chooseLabel, final ChecksumInput input) {
    this.input = input;
    shell = Dialogs.shell(parent, title, false);
    if (!note.isEmpty()) {
      Dialogs.label(shell, note);
    }
    source = Dialogs.label(shell, "");
    lines = new Text(shell, SWT.MULTI | SWT.READ_ONLY | SWT.BORDER | SWT.V_SCROLL | SWT.H_SCROLL);
    final Font mono =
        new Font(shell.getDisplay(), Dialogs.monospaceName(), Dialogs.monospaceSize(), SWT.NORMAL);
    lines.setFont(mono);
    GridData data = new GridData(SWT.FILL, SWT.FILL, true, true);
    data.widthHint = 640;
    data.heightHint = 16 * lines.getLineHeight();
    lines.setLayoutData(data);
    progress = new ProgressRow(shell, true);
    status = Dialogs.label(shell, "");
    Composite buttons = Dialogs.buttonRow(shell, 1);
    Dialogs.button(
        buttons,
        chooseLabel,
        new Runnable() {
          @Override
          public void run() {
            input.choose();
          }
        });
    Dialogs.button(
        buttons,
        UiText.BUTTON_COPY.text(),
        new Runnable() {
          @Override
          public void run() {
            input.copy();
          }
        });
    Dialogs.button(
        buttons,
        UiText.BUTTON_SAVE_AS.text(),
        new Runnable() {
          @Override
          public void run() {
            input.saveAs();
          }
        });
    Dialogs.button(buttons, UiText.BUTTON_CLOSE.text(), Dialogs.closer(shell));
    shell.addListener(
        SWT.Traverse,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            if (event.detail == SWT.TRAVERSE_ESCAPE) {
              event.doit = false;
              shell.dispose();
            }
          }
        });
    shell.addListener(
        SWT.Dispose,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            mono.dispose();
            input.closed();
          }
        });
  }

  void open() {
    Dialogs.open(shell, SWT.DEFAULT, SWT.DEFAULT);
  }

  Shell shell() {
    return shell;
  }

  /**
   * Returns the shown lines, for tests.
   *
   * @return the field's text with the platform's line delimiter
   */
  String lines() {
    return lines.getText();
  }

  /**
   * Returns the status line, for tests.
   *
   * @return the totals or a message
   */
  String status() {
    return status.getText();
  }

  /**
   * Returns the progress row, for tests.
   *
   * @return the row
   */
  ProgressRow progress() {
    return progress;
  }

  @Override
  public void clear(String text) {
    if (!shell.isDisposed()) {
      source.setText(text.replace("&", "&&"));
      lines.setText("");
      status.setText("");
      shell.layout(true, true);
    }
  }

  @Override
  public void addLines(List<String> added) {
    if (shell.isDisposed()) {
      return;
    }
    StringBuilder text = new StringBuilder();
    for (String line : added) {
      text.append(line).append(Text.DELIMITER);
    }
    lines.append(text.toString());
  }

  @Override
  public void showProgress(String text, int percent) {
    if (!shell.isDisposed()) {
      progress.show(
          text,
          percent,
          new Runnable() {
            @Override
            public void run() {
              input.cancel();
            }
          });
    }
  }

  @Override
  public void showStatus(String text) {
    if (!shell.isDisposed()) {
      progress.hide();
      status.setText(text.replace("&", "&&"));
      shell.layout(true, true);
    }
  }
}
