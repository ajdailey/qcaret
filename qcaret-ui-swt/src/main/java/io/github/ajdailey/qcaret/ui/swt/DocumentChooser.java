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
 * Asks which open document to compare the selected one with, with Compare and Cancel. Modal, opened
 * without a nested event loop; Enter or a double click compares with the selected document.
 */
final class DocumentChooser {
  private final Shell shell;
  private final org.eclipse.swt.widgets.List list;
  private final Consumer<Integer> chosen;

  DocumentChooser(Shell parent, List<String> names, Consumer<Integer> chosen) {
    this.chosen = chosen;
    shell = Dialogs.shell(parent, UiText.COMPARE_CHOOSE_TITLE.text(), true);
    Dialogs.label(shell, UiText.COMPARE_CHOOSE_LABEL.text());
    list = new org.eclipse.swt.widgets.List(shell, SWT.SINGLE | SWT.BORDER | SWT.V_SCROLL);
    GridData data = new GridData(SWT.FILL, SWT.FILL, true, true);
    data.heightHint = 200;
    data.widthHint = 460;
    list.setLayoutData(data);
    for (String name : names) {
      list.add(name);
    }
    list.select(0);
    list.addListener(
        SWT.DefaultSelection,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            choose();
          }
        });
    Composite row = Dialogs.buttonRow(shell, 1);
    Button compare =
        Dialogs.button(
            row,
            UiText.BUTTON_COMPARE.text(),
            new Runnable() {
              @Override
              public void run() {
                choose();
              }
            });
    Dialogs.button(row, UiText.BUTTON_CANCEL.text(), Dialogs.closer(shell));
    shell.setDefaultButton(compare);
  }

  private void choose() {
    int at = list.getSelectionIndex();
    if (at >= 0) {
      // Close first, so the comparison opens over the main window, not behind this dialog.
      shell.dispose();
      chosen.accept(at);
    }
  }

  void open() {
    Dialogs.open(shell, SWT.DEFAULT, SWT.DEFAULT);
  }

  Shell shell() {
    return shell;
  }

  /**
   * Chooses a document, for tests.
   *
   * @param index the document's row
   */
  void choose(int index) {
    list.select(index);
    choose();
  }
}
