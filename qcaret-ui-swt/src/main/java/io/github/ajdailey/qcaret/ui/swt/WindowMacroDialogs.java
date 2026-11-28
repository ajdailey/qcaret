package io.github.ajdailey.qcaret.ui.swt;

import io.github.ajdailey.qcaret.app.command.Keymap;
import io.github.ajdailey.qcaret.app.view.MacroManagerDisplay;
import io.github.ajdailey.qcaret.app.view.MacroManagerInput;
import io.github.ajdailey.qcaret.app.view.RunMacroForm;
import io.github.ajdailey.qcaret.app.view.SaveMacroForm;
import java.util.function.Consumer;
import java.util.function.Function;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;

/**
 * One main window's Macros dialogs ({@link RunMacroDialog}, {@link SaveMacroDialog}, {@link
 * ManageMacrosDialog}) and its status-bar macro field, which is left out of the bar's layout while
 * empty. Call on the UI thread.
 */
final class WindowMacroDialogs {
  private final Shell shell;
  private final Label status;
  private Keymap keymap;
  private RunMacroDialog runDialog;
  private SaveMacroDialog saveDialog;
  private ManageMacrosDialog manageDialog;

  /**
   * Creates the macro field as the status bar's next cell.
   *
   * @param shell the main window
   * @param statusBar the status bar, laid out with a grid
   */
  WindowMacroDialogs(Shell shell, Composite statusBar) {
    this.shell = shell;
    status = new Label(statusBar, SWT.NONE);
    GridData data = new GridData(SWT.END, SWT.CENTER, false, false);
    data.exclude = true;
    status.setLayoutData(data);
    status.setVisible(false);
  }

  /** Sets the keys the key fields write. */
  void setKeymap(Keymap value) {
    keymap = value;
  }

  void showStatus(String text) {
    if (status.isDisposed() || status.getText().equals(text)) {
      return;
    }
    boolean shown = !text.isEmpty();
    status.setText(text);
    status.setVisible(shown);
    ((GridData) status.getLayoutData()).exclude = !shown;
    StatusUpdates.layout(status.getParent());
  }

  void askRunMacro(
      Shell owner,
      RunMacroForm initial,
      Function<RunMacroForm, String> problem,
      Consumer<RunMacroForm> answer) {
    if (!shell.isDisposed()) {
      runDialog = new RunMacroDialog(owner, initial, problem, answer);
      runDialog.open();
    }
  }

  void askSaveMacro(
      Shell owner,
      SaveMacroForm initial,
      Function<SaveMacroForm, String> problem,
      Consumer<SaveMacroForm> answer) {
    if (!shell.isDisposed()) {
      saveDialog = new SaveMacroDialog(owner, keymap, initial, problem, answer);
      saveDialog.open();
    }
  }

  MacroManagerDisplay showManageMacros(Shell owner, MacroManagerInput input) {
    if (shell.isDisposed()) {
      return new MacroManagerDisplay() {
        @Override
        public void rowsChanged() {
          // No dialog.
        }

        @Override
        public void showMessage(String text) {
          // No dialog.
        }
      };
    }
    manageDialog = new ManageMacrosDialog(owner, keymap, input);
    manageDialog.open();
    return manageDialog;
  }

  Label status() {
    return status;
  }

  RunMacroDialog runDialog() {
    return runDialog;
  }

  SaveMacroDialog saveDialog() {
    return saveDialog;
  }

  ManageMacrosDialog manageDialog() {
    return manageDialog;
  }
}
