package io.github.ajdailey.qcaret.ui.swt;

import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.view.SequenceForm;
import io.github.ajdailey.qcaret.ui.swt.platform.ComboListSelection;
import java.util.function.Consumer;
import java.util.function.Predicate;
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
import org.eclipse.swt.widgets.Spinner;
import org.eclipse.swt.widgets.Text;

/**
 * Edit > Insert Number Sequence: a modal form for the start, step, zero-padded width and base. OK
 * is enabled while the controller's validator accepts the fields; it passes them to the answer and
 * closes. Cancel and Esc close without an answer. Opens without a nested event loop.
 */
final class NumberSequenceDialog {
  /** Widest zero padding the width field offers. */
  private static final int MAX_WIDTH = 64;

  private final Shell shell;
  private final Text start;
  private final Text step;
  private final Spinner width;
  private final Combo base;
  private final Button ok;
  private final Predicate<SequenceForm> valid;
  private final Consumer<SequenceForm> answer;

  NumberSequenceDialog(
      Shell parent,
      SequenceForm initial,
      Predicate<SequenceForm> valid,
      Consumer<SequenceForm> answer) {
    this.valid = valid;
    this.answer = answer;
    shell = Dialogs.shell(parent, UiText.NUMBER_SEQUENCE_TITLE.text(), true);
    Composite form = new Composite(shell, SWT.NONE);
    GridLayout layout = new GridLayout(2, false);
    layout.marginWidth = 0;
    layout.marginHeight = 0;
    form.setLayout(layout);
    form.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    start = field(form, UiText.NUMBER_SEQUENCE_START, initial.start());
    step = field(form, UiText.NUMBER_SEQUENCE_STEP, initial.step());
    label(form, UiText.NUMBER_SEQUENCE_WIDTH);
    width = new Spinner(form, SWT.BORDER);
    width.setValues(initial.width(), 0, MAX_WIDTH, 0, 1, 4);
    label(form, UiText.NUMBER_SEQUENCE_BASE);
    base = new Combo(form, SWT.READ_ONLY);
    ComboListSelection.PLATFORM.draw(base);
    // In SequenceForm.base order.
    base.add(UiText.NUMBER_SEQUENCE_DECIMAL.text());
    base.add(UiText.NUMBER_SEQUENCE_HEX_UPPER.text());
    base.add(UiText.NUMBER_SEQUENCE_HEX_LOWER.text());
    base.select(initial.base());
    Dialogs.label(shell, UiText.NUMBER_SEQUENCE_NOTE.text());
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
            ok.setEnabled(NumberSequenceDialog.this.valid.test(values()));
          }
        };
    start.addListener(SWT.Modify, check);
    step.addListener(SWT.Modify, check);
    ok.setEnabled(valid.test(values()));
  }

  private static Text field(Composite form, UiText name, String value) {
    label(form, name);
    Text text = new Text(form, SWT.SINGLE | SWT.BORDER);
    GridData data = new GridData(SWT.FILL, SWT.CENTER, true, false);
    data.widthHint = 200;
    text.setLayoutData(data);
    text.setText(value);
    return text;
  }

  private static void label(Composite form, UiText name) {
    Label label = new Label(form, SWT.NONE);
    label.setText(name.text());
  }

  private SequenceForm values() {
    return new SequenceForm(
        start.getText(), step.getText(), width.getSelection(), base.getSelectionIndex());
  }

  /** Passes the fields to the answer and closes; does nothing while the validator refuses them. */
  void submit() {
    SequenceForm values = values();
    if (valid.test(values)) {
      // Close first, so the editor has the focus again when the numbers arrive.
      shell.dispose();
      answer.accept(values);
    }
  }

  void open() {
    Dialogs.open(shell, SWT.DEFAULT, SWT.DEFAULT);
    start.setFocus();
    start.selectAll();
  }

  Shell shell() {
    return shell;
  }

  Text start() {
    return start;
  }

  Text step() {
    return step;
  }

  Spinner width() {
    return width;
  }

  Combo base() {
    return base;
  }

  Button ok() {
    return ok;
  }
}
