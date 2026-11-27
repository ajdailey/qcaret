package io.github.ajdailey.qcaret.ui.swt.hex;

import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.view.HexInput;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Text;

/**
 * A hex view's find bar: the pattern, the Hex bytes box (off: text in the source's charset),
 * Previous and Next, and the find message. Enter finds the next match and Shift+Enter the previous;
 * Esc stops a running find, hides the bar and focuses the rows. Hidden and left out of the layout
 * until {@link #show}. Call on the UI thread.
 */
final class HexFindBar {
  private final Composite bar;
  private final Text field;
  private final Button hex;
  private final Label message;
  private final HexInput input;
  private final Runnable focusRows;

  HexFindBar(Composite parent, final HexInput input, Runnable focusRows) {
    this.input = input;
    this.focusRows = focusRows;
    bar = new Composite(parent, SWT.NONE);
    GridLayout layout = new GridLayout(6, false);
    layout.marginHeight = 2;
    bar.setLayout(layout);
    new Label(bar, SWT.NONE).setText(UiText.FIND_LABEL.text());
    field = new Text(bar, SWT.SINGLE | SWT.BORDER);
    GridData fieldData = new GridData(SWT.FILL, SWT.CENTER, false, false);
    fieldData.widthHint = 220;
    field.setLayoutData(fieldData);
    hex = new Button(bar, SWT.CHECK);
    hex.setText(UiText.HEX_FIND_AS_HEX.text());
    button(UiText.BUTTON_PREVIOUS, false);
    button(UiText.BUTTON_NEXT, true);
    message = new Label(bar, SWT.NONE);
    message.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    field.addListener(
        SWT.Traverse,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            if (event.detail == SWT.TRAVERSE_RETURN) {
              event.doit = false;
              find((event.stateMask & SWT.SHIFT) == 0);
            } else if (event.detail == SWT.TRAVERSE_ESCAPE) {
              event.doit = false;
              hide();
            }
          }
        });
    GridData data = new GridData(SWT.FILL, SWT.CENTER, true, false);
    data.exclude = true;
    bar.setLayoutData(data);
    bar.setVisible(false);
  }

  private void button(UiText label, final boolean forward) {
    Button button = new Button(bar, SWT.PUSH);
    button.setText(label.text());
    button.addListener(
        SWT.Selection,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            find(forward);
          }
        });
  }

  Composite control() {
    return bar;
  }

  Text field() {
    return field;
  }

  Button hexBox() {
    return hex;
  }

  Label message() {
    return message;
  }

  /**
   * Shows the bar with a pattern and focuses its field.
   *
   * @param text the pattern
   * @param hexPattern true for hex pairs
   */
  void show(String text, boolean hexPattern) {
    field.setText(text);
    field.selectAll();
    hex.setSelection(hexPattern);
    setShown(true);
    field.setFocus();
  }

  /** Shows a find message, showing the bar for a message that is not empty. */
  void showMessage(String text) {
    message.setText(text);
    if (!text.isEmpty()) {
      setShown(true);
    }
  }

  /**
   * Finds the field's pattern; with an empty field, shows the bar.
   *
   * @param forward true for Next
   */
  void find(boolean forward) {
    if (field.getText().isEmpty()) {
      show("", hex.getSelection());
    } else {
      input.find(field.getText(), hex.getSelection(), forward);
    }
  }

  private void hide() {
    input.cancel();
    setShown(false);
    focusRows.run();
  }

  private void setShown(boolean shown) {
    if (bar.getVisible() != shown) {
      ((GridData) bar.getLayoutData()).exclude = !shown;
      bar.setVisible(shown);
      bar.getParent().layout(true, true);
    }
  }
}
