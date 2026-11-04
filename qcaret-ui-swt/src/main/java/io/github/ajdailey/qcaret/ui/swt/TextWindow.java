package io.github.ajdailey.qcaret.ui.swt;

import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.view.ClipboardService;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

/**
 * A resizable window of read-only monospace text with Copy and Close: usage text, licenses, the
 * log. Modeless, or modal when opened over a modal dialog: GTK gives input only to the newest modal
 * shell, so a modeless window opened from one shows behind it and takes no input.
 */
final class TextWindow {
  private final Shell shell;
  private final Text text;
  private final Button close;
  private String content;

  TextWindow(Shell parent, String title, String initial, final ClipboardService clipboard) {
    this(parent, title, initial, clipboard, false);
  }

  TextWindow(
      Shell parent, String title, String initial, final ClipboardService clipboard, boolean modal) {
    shell =
        Dialogs.shell(
            parent, title, modal ? SWT.SHELL_TRIM | SWT.APPLICATION_MODAL : SWT.SHELL_TRIM);
    content = initial;
    text = new Text(shell, SWT.MULTI | SWT.READ_ONLY | SWT.BORDER | SWT.V_SCROLL | SWT.H_SCROLL);
    final Font font =
        new Font(shell.getDisplay(), Dialogs.monospaceName(), Dialogs.monospaceSize(), SWT.NORMAL);
    text.setFont(font);
    text.setText(content);
    text.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
    Composite buttons = Dialogs.buttonRow(shell, 1);
    Dialogs.button(
        buttons,
        UiText.BUTTON_COPY.text(),
        new Runnable() {
          @Override
          public void run() {
            clipboard.setText(content);
          }
        });
    close = Dialogs.button(buttons, UiText.BUTTON_CLOSE.text(), Dialogs.closer(shell));
    shell.setDefaultButton(close);
    shell.addListener(
        SWT.Dispose,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            font.dispose();
          }
        });
  }

  void open() {
    Dialogs.open(shell, 720, 520);
  }

  Shell shell() {
    return shell;
  }

  /**
   * Replaces the text, shows its first line and focuses Close.
   *
   * @param value the new text
   */
  void setContent(String value) {
    content = value;
    text.setText(value);
    text.setSelection(0);
    text.setTopIndex(0);
    close.setFocus();
  }

  /** Shows the first line and focuses Close. */
  void showStart() {
    text.setSelection(0);
    text.setTopIndex(0);
    close.setFocus();
  }
}
