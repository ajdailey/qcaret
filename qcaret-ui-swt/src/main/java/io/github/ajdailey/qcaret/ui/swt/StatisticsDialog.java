package io.github.ajdailey.qcaret.ui.swt;

import io.github.ajdailey.qcaret.app.editor.TextStatistics;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.view.StatisticsDisplay;
import io.github.ajdailey.qcaret.app.view.StatisticsInput;
import io.github.ajdailey.qcaret.app.view.StatisticsReport;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;

/**
 * Tools > Document Statistics: a modeless window with the counts of the document and of its
 * selections in two columns, a status line for the percent read or a message, Refresh and Close.
 * Esc closes it; closing calls {@link StatisticsInput#closed}.
 */
final class StatisticsDialog implements StatisticsDisplay {
  /** A display for a disposed window: shows nothing. */
  static final StatisticsDisplay NONE =
      new StatisticsDisplay() {
        @Override
        public void showProgress(int percent) {
          // Nothing shows.
        }

        @Override
        public void showReport(StatisticsReport report) {
          // Nothing shows.
        }

        @Override
        public void showMessage(String message) {
          // Nothing shows.
        }

        @Override
        public void toFront() {
          // Nothing shows.
        }
      };

  private static final UiText[] ROWS = {
    UiText.STATISTICS_LINES,
    UiText.STATISTICS_WORDS,
    UiText.STATISTICS_CHARS,
    UiText.STATISTICS_NON_SPACE_CHARS,
    UiText.STATISTICS_BYTES
  };

  private final Shell shell;
  private final Label heading;
  private final Label[] document = new Label[ROWS.length];
  private final Label[] selection = new Label[ROWS.length];
  private final Label status;

  StatisticsDialog(Shell parent, final StatisticsInput input) {
    shell = Dialogs.shell(parent, UiText.STATISTICS_TITLE.text(), false);
    heading = Dialogs.label(shell, "");
    Composite grid = new Composite(shell, SWT.NONE);
    GridLayout layout = new GridLayout(3, false);
    layout.marginWidth = 0;
    layout.horizontalSpacing = 16;
    grid.setLayout(layout);
    grid.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    new Label(grid, SWT.NONE);
    value(grid, UiText.STATISTICS_DOCUMENT.text());
    value(grid, UiText.STATISTICS_SELECTION.text());
    for (int i = 0; i < ROWS.length; i++) {
      new Label(grid, SWT.NONE).setText(ROWS[i].text());
      document[i] = value(grid, "");
      selection[i] = value(grid, "");
    }
    status = Dialogs.label(shell, "");
    Composite buttons = Dialogs.buttonRow(shell, 1);
    Dialogs.button(
        buttons,
        UiText.BUTTON_REFRESH.text(),
        new Runnable() {
          @Override
          public void run() {
            input.refresh();
          }
        });
    shell.setDefaultButton(
        Dialogs.button(buttons, UiText.BUTTON_CLOSE.text(), Dialogs.closer(shell)));
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
            input.closed();
          }
        });
  }

  private static Label value(Composite grid, String text) {
    Label label = new Label(grid, SWT.RIGHT);
    label.setText(text);
    GridData data = new GridData(SWT.FILL, SWT.CENTER, true, false);
    data.widthHint = 110;
    label.setLayoutData(data);
    return label;
  }

  void open() {
    Dialogs.open(shell, SWT.DEFAULT, SWT.DEFAULT);
  }

  Shell shell() {
    return shell;
  }

  /**
   * Returns the shown counts, for tests.
   *
   * @return the document column, then the selection column, top to bottom
   */
  String[] values() {
    String[] out = new String[2 * ROWS.length];
    for (int i = 0; i < ROWS.length; i++) {
      out[i] = document[i].getText();
      out[ROWS.length + i] = selection[i].getText();
    }
    return out;
  }

  /**
   * Returns the status line, for tests.
   *
   * @return the progress or message text
   */
  String status() {
    return status.getText();
  }

  @Override
  public void showProgress(int percent) {
    if (!shell.isDisposed()) {
      status.setText(UiText.STATISTICS_COUNTING.format(percent));
    }
  }

  @Override
  public void showReport(StatisticsReport report) {
    if (shell.isDisposed()) {
      return;
    }
    heading.setText(UiText.STATISTICS_HEADING.format(report.name(), report.encoding()));
    fill(document, report.document());
    fill(selection, report.selection());
    status.setText("");
    shell.layout(true, true);
  }

  private static void fill(Label[] column, TextStatistics counts) {
    long[] values =
        counts == null
            ? null
            : new long[] {
              counts.lines(), counts.words(), counts.chars(), counts.nonSpaceChars(), counts.bytes()
            };
    for (int i = 0; i < column.length; i++) {
      if (values == null) {
        column[i].setText("");
      } else if (values[i] == TextStatistics.UNKNOWN_BYTES) {
        column[i].setText(UiText.STATISTICS_BYTES_UNKNOWN.text());
      } else {
        column[i].setText(String.format("%,d", values[i]));
      }
    }
  }

  @Override
  public void showMessage(String message) {
    if (!shell.isDisposed()) {
      fill(document, null);
      fill(selection, null);
      status.setText(message);
      shell.layout(true, true);
    }
  }

  @Override
  public void toFront() {
    if (!shell.isDisposed()) {
      shell.setActive();
    }
  }
}
