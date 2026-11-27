package io.github.ajdailey.qcaret.ui.swt;

import io.github.ajdailey.qcaret.app.command.Keymap;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.view.SaveMacroForm;
import java.util.function.Consumer;
import java.util.function.Function;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

/**
 * Macros > Save Recorded Macro: a modal form for the name and an optional key, taken by a {@link
 * KeyField}. OK is enabled while the controller's check finds no problem; the problem shows under
 * the fields. OK passes the fields to the answer and closes; Cancel and Esc close without one.
 * Opens without a nested event loop.
 */
final class SaveMacroDialog {
  private final Shell shell;
  private final Text name;
  private final KeyField key;
  private final Label problemLabel;
  private final Button ok;
  private final Function<SaveMacroForm, String> problem;
  private final Consumer<SaveMacroForm> answer;

  SaveMacroDialog(
      Shell parent,
      Keymap keymap,
      SaveMacroForm initial,
      Function<SaveMacroForm, String> problem,
      Consumer<SaveMacroForm> answer) {
    this.problem = problem;
    this.answer = answer;
    shell = Dialogs.shell(parent, UiText.SAVE_MACRO_TITLE.text(), true);
    Composite form = new Composite(shell, SWT.NONE);
    GridLayout layout = new GridLayout(3, false);
    layout.marginWidth = 0;
    layout.marginHeight = 0;
    form.setLayout(layout);
    form.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    new Label(form, SWT.NONE).setText(UiText.SAVE_MACRO_NAME.text());
    name = new Text(form, SWT.SINGLE | SWT.BORDER);
    GridData nameData = new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1);
    nameData.widthHint = 240;
    name.setLayoutData(nameData);
    name.setText(initial.name());
    new Label(form, SWT.NONE).setText(UiText.SAVE_MACRO_KEY.text());
    Runnable check =
        new Runnable() {
          @Override
          public void run() {
            check();
          }
        };
    key = new KeyField(form, keymap, check);
    Dialogs.button(
        form,
        UiText.BUTTON_CLEAR_KEY.text(),
        new Runnable() {
          @Override
          public void run() {
            key.set(null);
          }
        });
    problemLabel = Dialogs.label(shell, "");
    Composite buttons = Dialogs.buttonRow(shell, 1);
    ok =
        Dialogs.button(
            buttons,
            UiText.BUTTON_OK.text(),
            new Runnable() {
              @Override
              public void run() {
                submit();
              }
            });
    Dialogs.button(buttons, UiText.BUTTON_CANCEL.text(), Dialogs.closer(shell));
    shell.setDefaultButton(ok);
    name.addListener(
        SWT.Modify,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            check();
          }
        });
    key.set(initial.key());
  }

  private SaveMacroForm values() {
    return new SaveMacroForm(name.getText(), key.stroke());
  }

  private void check() {
    String text = problem.apply(values());
    problemLabel.setText(text == null ? "" : text);
    ok.setEnabled(text == null);
    shell.layout(true, true);
  }

  /** Passes the fields to the answer and closes; does nothing while the check refuses them. */
  void submit() {
    SaveMacroForm values = values();
    if (problem.apply(values) == null) {
      shell.dispose();
      answer.accept(values);
    }
  }

  void open() {
    Dialogs.open(shell, SWT.DEFAULT, SWT.DEFAULT);
    name.setFocus();
  }

  Shell shell() {
    return shell;
  }

  Text name() {
    return name;
  }

  KeyField key() {
    return key;
  }

  Label problem() {
    return problemLabel;
  }

  Button ok() {
    return ok;
  }
}
