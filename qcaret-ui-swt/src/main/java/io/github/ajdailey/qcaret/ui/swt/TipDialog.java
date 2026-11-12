package io.github.ajdailey.qcaret.ui.swt;

import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.view.TipDisplay;
import io.github.ajdailey.qcaret.app.view.TipInput;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontData;
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
 * Help > Tip of the Day: a modeless window with a heading, the tip in a read-only text area, the
 * Show tips on startup check box, Next Tip and Close. Esc closes it; closing calls {@link
 * TipInput#closed}.
 */
final class TipDialog implements TipDisplay {
  /** A display for a disposed window: shows nothing. */
  static final TipDisplay NONE =
      new TipDisplay() {
        @Override
        public void showTip(String text, boolean onStartup) {
          // Nothing shows.
        }

        @Override
        public void toFront() {
          // Nothing shows.
        }
      };

  private static final int TEXT_WIDTH = 420;
  private static final int TEXT_LINES = 4;

  private final Shell shell;
  private final Text tip;
  private final Button startup;
  private final Button next;

  TipDialog(Shell parent, final TipInput input) {
    shell = Dialogs.shell(parent, UiText.TIP_TITLE.text(), false);
    Label heading = Dialogs.label(shell, UiText.TIP_HEADING.text());
    FontData[] data = heading.getFont().getFontData();
    for (FontData each : data) {
      each.setHeight(each.getHeight() + 2);
      each.setStyle(SWT.BOLD);
    }
    final Font bold = new Font(shell.getDisplay(), data);
    heading.setFont(bold);
    tip = new Text(shell, SWT.MULTI | SWT.READ_ONLY | SWT.WRAP | SWT.BORDER | SWT.V_SCROLL);
    GridData area = new GridData(SWT.FILL, SWT.FILL, true, true);
    area.widthHint = TEXT_WIDTH;
    area.heightHint = TEXT_LINES * tip.getLineHeight();
    tip.setLayoutData(area);
    Composite row = new Composite(shell, SWT.NONE);
    GridLayout layout = new GridLayout(3, false);
    layout.marginWidth = 0;
    layout.marginHeight = 0;
    row.setLayout(layout);
    row.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    startup = new Button(row, SWT.CHECK);
    startup.setText(UiText.TIP_ON_STARTUP.text());
    startup.setLayoutData(new GridData(SWT.BEGINNING, SWT.CENTER, true, false));
    startup.addListener(
        SWT.Selection,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            input.showOnStartup(startup.getSelection());
          }
        });
    next =
        Dialogs.button(
            row,
            UiText.BUTTON_NEXT_TIP.text(),
            new Runnable() {
              @Override
              public void run() {
                input.nextTip();
              }
            });
    shell.setDefaultButton(Dialogs.button(row, UiText.BUTTON_CLOSE.text(), Dialogs.closer(shell)));
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
            bold.dispose();
            input.closed();
          }
        });
  }

  void open() {
    Dialogs.open(shell, SWT.DEFAULT, SWT.DEFAULT);
    next.setFocus();
  }

  Shell shell() {
    return shell;
  }

  /**
   * Returns the shown tip, for tests.
   *
   * @return the text area's text
   */
  String tip() {
    return tip.getText();
  }

  /**
   * Returns the check box, for tests.
   *
   * @return Show tips on startup
   */
  Button startup() {
    return startup;
  }

  /**
   * Returns the Next Tip button, for tests.
   *
   * @return the button
   */
  Button next() {
    return next;
  }

  @Override
  public void showTip(String text, boolean onStartup) {
    if (!shell.isDisposed()) {
      tip.setText(text);
      startup.setSelection(onStartup);
    }
  }

  @Override
  public void toFront() {
    if (!shell.isDisposed()) {
      shell.setActive();
    }
  }
}
