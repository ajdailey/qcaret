package io.github.ajdailey.qcaret.ui.swt;

import io.github.ajdailey.qcaret.app.i18n.UiText;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.ProgressBar;
import org.eclipse.swt.widgets.Shell;

/**
 * A window's row for running work: what is running, a progress bar and Cancel, hidden while nothing
 * shows. A row made with Esc on runs Cancel for Esc in any control of the shell while it shows and
 * the key goes no further, so a window without a document can stop a load too.
 */
final class ProgressRow {
  private final Shell shell;
  private final Composite root;
  private final Label label;
  private final ProgressBar bar;
  private final Button cancel;
  private Runnable action;

  /**
   * Creates the row, hidden.
   *
   * @param shell the window
   * @param escapeCancels true when Esc runs Cancel while the row shows
   */
  ProgressRow(final Shell shell, boolean escapeCancels) {
    this.shell = shell;
    root = new Composite(shell, SWT.NONE);
    GridData data = new GridData(SWT.FILL, SWT.CENTER, true, false);
    data.exclude = true;
    root.setLayoutData(data);
    root.setVisible(false);
    GridLayout layout = new GridLayout(3, false);
    layout.marginHeight = 2;
    layout.marginWidth = 6;
    root.setLayout(layout);
    label = new Label(root, SWT.NONE);
    label.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    bar = new ProgressBar(root, SWT.HORIZONTAL);
    bar.setMaximum(100);
    GridData barData = new GridData(SWT.END, SWT.CENTER, false, false);
    barData.widthHint = 200;
    bar.setLayoutData(barData);
    cancel =
        Dialogs.button(
            root,
            UiText.BUTTON_CANCEL.text(),
            new Runnable() {
              @Override
              public void run() {
                action.run();
              }
            });
    if (escapeCancels) {
      cancelOnEscape();
    }
  }

  private void cancelOnEscape() {
    final Display display = shell.getDisplay();
    final Listener escape =
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            if (event.keyCode == SWT.ESC
                && root.getVisible()
                && event.widget instanceof Control control
                && !control.isDisposed()
                && control.getShell() == shell) {
              event.type = SWT.None;
              event.doit = false;
              action.run();
            }
          }
        };
    display.addFilter(SWT.KeyDown, escape);
    shell.addListener(
        SWT.Dispose,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            display.removeFilter(SWT.KeyDown, escape);
          }
        });
  }

  Composite control() {
    return root;
  }

  /** Shows the row, or updates it, with what Cancel runs. */
  void show(String text, int percent, Runnable cancelRun) {
    action = cancelRun;
    label.setText(text.replace("&", "&&"));
    bar.setSelection(percent);
    if (!root.getVisible()) {
      setShown(true);
    }
  }

  void hide() {
    if (root.getVisible()) {
      setShown(false);
    }
  }

  private void setShown(boolean shown) {
    ((GridData) root.getLayoutData()).exclude = !shown;
    root.setVisible(shown);
    shell.layout(true);
  }

  boolean shown() {
    return root.getVisible();
  }

  String text() {
    return label.getText();
  }

  int percent() {
    return bar.getSelection();
  }

  Button cancelButton() {
    return cancel;
  }
}
