package io.github.ajdailey.qcaret.ui.swt;

import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.view.HashTextDisplay;
import io.github.ajdailey.qcaret.app.view.HashTextInput;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

/**
 * Tools > Generate: a modeless window with a note, a single-line text field, its hash in a
 * read-only monospace field, Copy and Close. Each change of the field calls {@link
 * HashTextInput#textChanged}; a single-line field reads the same text on every platform. Esc closes
 * it; closing calls {@link HashTextInput#closed}.
 */
final class HashTextDialog implements HashTextDisplay {
  /** A display for a disposed window: shows nothing. */
  static final HashTextDisplay NONE =
      new HashTextDisplay() {
        @Override
        public void showHash(String hex) {
          // Nothing shows.
        }
      };

  private final Shell shell;
  private final Text field;
  private final Text hash;

  HashTextDialog(Shell parent, String title, String note, final HashTextInput input) {
    shell = Dialogs.shell(parent, title, false);
    ((GridData) Dialogs.label(shell, note).getLayoutData()).widthHint = 460;
    field = new Text(shell, SWT.SINGLE | SWT.BORDER);
    field.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    field.addListener(
        SWT.Modify,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            input.textChanged(field.getText());
          }
        });
    hash = new Text(shell, SWT.MULTI | SWT.WRAP | SWT.READ_ONLY | SWT.BORDER);
    final Font mono =
        new Font(shell.getDisplay(), Dialogs.monospaceName(), Dialogs.monospaceSize(), SWT.NORMAL);
    hash.setFont(mono);
    GridData hashData = new GridData(SWT.FILL, SWT.CENTER, true, false);
    hashData.widthHint = 460;
    hashData.heightHint = 2 * hash.getLineHeight();
    hash.setLayoutData(hashData);
    Composite buttons = Dialogs.buttonRow(shell, 1);
    Dialogs.button(
        buttons,
        UiText.BUTTON_COPY.text(),
        new Runnable() {
          @Override
          public void run() {
            input.copy();
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
    field.setFocus();
  }

  Shell shell() {
    return shell;
  }

  /**
   * Returns the text field, for tests.
   *
   * @return the field
   */
  Text field() {
    return field;
  }

  /**
   * Returns the shown hash, for tests.
   *
   * @return the hex, or empty before the first result
   */
  String hash() {
    return hash.getText();
  }

  @Override
  public void showHash(String hex) {
    if (!shell.isDisposed()) {
      hash.setText(hex);
    }
  }
}
