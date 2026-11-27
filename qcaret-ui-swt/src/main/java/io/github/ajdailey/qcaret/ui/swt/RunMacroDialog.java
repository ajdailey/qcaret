package io.github.ajdailey.qcaret.ui.swt;

import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.view.RunMacroForm;
import io.github.ajdailey.qcaret.ui.swt.platform.ComboListSelection;
import java.util.function.Consumer;
import java.util.function.Function;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

/**
 * Macros > Run Multiple Times: a modal form for the macro and either a count or a run to the end of
 * the document. OK is enabled while the controller's check finds no problem; the problem shows
 * under the fields. OK passes the fields to the answer and closes; Cancel and Esc close without
 * one. Opens without a nested event loop.
 */
final class RunMacroDialog {
  private final Shell shell;
  private final Combo macro;
  private final Button times;
  private final Text count;
  private final Button toEnd;
  private final Label problemLabel;
  private final Button ok;
  private final RunMacroForm initial;
  private final Function<RunMacroForm, String> problem;
  private final Consumer<RunMacroForm> answer;

  RunMacroDialog(
      Shell parent,
      RunMacroForm initial,
      Function<RunMacroForm, String> problem,
      Consumer<RunMacroForm> answer) {
    this.initial = initial;
    this.problem = problem;
    this.answer = answer;
    shell = Dialogs.shell(parent, UiText.RUN_MACRO_TITLE.text(), true);
    Composite form = new Composite(shell, SWT.NONE);
    GridLayout layout = new GridLayout(2, false);
    layout.marginWidth = 0;
    layout.marginHeight = 0;
    form.setLayout(layout);
    form.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    new Label(form, SWT.NONE).setText(UiText.RUN_MACRO_MACRO.text());
    macro = new Combo(form, SWT.READ_ONLY);
    ComboListSelection.PLATFORM.draw(macro);
    for (String name : initial.macros()) {
      macro.add(name.replace("&", "&&"));
    }
    macro.select(initial.macro());
    macro.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    times = new Button(form, SWT.RADIO);
    times.setText(UiText.RUN_MACRO_TIMES.text());
    count = new Text(form, SWT.SINGLE | SWT.BORDER);
    GridData countData = new GridData(SWT.FILL, SWT.CENTER, true, false);
    countData.widthHint = 120;
    count.setLayoutData(countData);
    count.setText(initial.count());
    toEnd = new Button(form, SWT.RADIO);
    toEnd.setText(UiText.RUN_MACRO_TO_END.text());
    toEnd.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
    times.setSelection(!initial.toEnd());
    toEnd.setSelection(initial.toEnd());
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
    Listener check =
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            check();
          }
        };
    count.addListener(SWT.Modify, check);
    times.addListener(SWT.Selection, check);
    toEnd.addListener(SWT.Selection, check);
    check();
  }

  private RunMacroForm values() {
    return new RunMacroForm(
        initial.macros(), macro.getSelectionIndex(), count.getText(), toEnd.getSelection());
  }

  private void check() {
    count.setEnabled(times.getSelection());
    String text = problem.apply(values());
    problemLabel.setText(text == null ? "" : text);
    ok.setEnabled(text == null);
    shell.layout(true, true);
  }

  /** Passes the fields to the answer and closes; does nothing while the check refuses them. */
  void submit() {
    RunMacroForm values = values();
    if (problem.apply(values) == null) {
      shell.dispose();
      answer.accept(values);
    }
  }

  void open() {
    Dialogs.open(shell, SWT.DEFAULT, SWT.DEFAULT);
    count.setFocus();
    count.selectAll();
  }

  Shell shell() {
    return shell;
  }

  Combo macro() {
    return macro;
  }

  Button times() {
    return times;
  }

  Text count() {
    return count;
  }

  Button toEnd() {
    return toEnd;
  }

  Label problem() {
    return problemLabel;
  }

  Button ok() {
    return ok;
  }
}
