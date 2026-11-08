package io.github.ajdailey.qcaret.ui.swt;

import io.github.ajdailey.qcaret.app.i18n.UiText;
import java.util.List;
import java.util.function.Consumer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;

/**
 * Edit > Insert Date and Time: a modal list of formats, each with the current time in it, with
 * Insert and Cancel. Opened without a nested event loop; Enter or a double click inserts the
 * selected format.
 */
final class DateFormatChooser {
  private final Shell shell;
  private final org.eclipse.swt.widgets.List list;
  private final Consumer<Integer> chosen;

  DateFormatChooser(Shell parent, List<String> rows, int selected, Consumer<Integer> chosen) {
    this.chosen = chosen;
    shell = Dialogs.shell(parent, UiText.DATE_FORMAT_TITLE.text(), true);
    Dialogs.label(shell, UiText.DATE_FORMAT_LABEL.text());
    list = new org.eclipse.swt.widgets.List(shell, SWT.SINGLE | SWT.BORDER | SWT.V_SCROLL);
    GridData data = new GridData(SWT.FILL, SWT.FILL, true, true);
    data.widthHint = 460;
    list.setLayoutData(data);
    for (String row : rows) {
      list.add(row);
    }
    list.select(Math.min(selected, rows.size() - 1));
    list.addListener(
        SWT.DefaultSelection,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            choose();
          }
        });
    Composite row = Dialogs.buttonRow(shell, 1);
    Button insert =
        Dialogs.button(
            row,
            UiText.BUTTON_INSERT.text(),
            new Runnable() {
              @Override
              public void run() {
                choose();
              }
            });
    Dialogs.button(row, UiText.BUTTON_CANCEL.text(), Dialogs.closer(shell));
    shell.setDefaultButton(insert);
  }

  private void choose() {
    int at = list.getSelectionIndex();
    if (at >= 0) {
      // Close first, so the editor has the focus again when the text arrives.
      shell.dispose();
      chosen.accept(at);
    }
  }

  void open() {
    Dialogs.open(shell, SWT.DEFAULT, SWT.DEFAULT);
    list.setFocus();
  }

  Shell shell() {
    return shell;
  }

  /**
   * Returns the selected row, for tests.
   *
   * @return the index
   */
  int selected() {
    return list.getSelectionIndex();
  }

  /**
   * Chooses a row, for tests.
   *
   * @param index the row
   */
  void choose(int index) {
    list.select(index);
    choose();
  }
}
