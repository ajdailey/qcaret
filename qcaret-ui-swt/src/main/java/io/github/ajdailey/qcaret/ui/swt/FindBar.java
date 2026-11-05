package io.github.ajdailey.qcaret.ui.swt;

import io.github.ajdailey.qcaret.app.controller.FindController;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.view.FindListener;
import io.github.ajdailey.qcaret.app.view.FindState;
import java.util.Arrays;
import java.util.List;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Listener;

/**
 * The find bar above the status bar, shown while {@link FindController} is open: the search field
 * with its history, Match Case, Whole Word and Regex, Previous, Next, the last notice and Close,
 * and for Replace a second row with the replacement field and its history, Replace and Replace All.
 * Typing or choosing a history entry finds incrementally; Enter finds the next match, Shift+Enter
 * the previous one, Enter in the replacement field replaces, and Escape anywhere in the bar closes
 * it. While a field's list is open, Enter and Escape go to the list.
 */
final class FindBar {
  /** Width of each text field. */
  static final int FIELD_WIDTH = 260;

  private final Composite bar;
  private final Composite replaceRow;
  private final Combo field;
  private final Combo replaceField;
  private final Button matchCase;
  private final Button wholeWord;
  private final Button regex;
  private final Button replaceButton;
  private final Button replaceAllButton;
  private final Label notice;
  private final FindController find;
  private boolean syncing;
  private final FindListener listener =
      new FindListener() {
        @Override
        public void findStateChanged(FindState state) {
          show(state);
        }

        @Override
        public void focusRequested() {
          focus();
        }
      };

  FindBar(Composite parent, FindController find) {
    this.find = find;
    bar = new Composite(parent, SWT.NONE);
    GridData barData = new GridData(SWT.FILL, SWT.CENTER, true, false);
    barData.exclude = true;
    bar.setLayoutData(barData);
    bar.setVisible(false);
    GridLayout barLayout = new GridLayout(1, false);
    barLayout.marginHeight = 2;
    barLayout.verticalSpacing = 2;
    bar.setLayout(barLayout);
    Composite findRow = row(9);
    label(findRow, UiText.FIND_LABEL);
    field = field(findRow);
    matchCase = check(findRow, UiText.FIND_MATCH_CASE);
    wholeWord = check(findRow, UiText.FIND_WHOLE_WORD);
    regex = check(findRow, UiText.FIND_REGEX);
    button(
        findRow,
        UiText.BUTTON_PREVIOUS,
        new Runnable() {
          @Override
          public void run() {
            FindBar.this.find.findPrevious();
          }
        });
    button(
        findRow,
        UiText.BUTTON_NEXT,
        new Runnable() {
          @Override
          public void run() {
            FindBar.this.find.findNext();
          }
        });
    notice = new Label(findRow, SWT.NONE);
    notice.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    button(
        findRow,
        UiText.BUTTON_CLOSE,
        new Runnable() {
          @Override
          public void run() {
            FindBar.this.find.close();
          }
        });
    replaceRow = row(4);
    label(replaceRow, UiText.FIND_REPLACE_LABEL);
    replaceField = field(replaceRow);
    replaceButton =
        button(
            replaceRow,
            UiText.BUTTON_REPLACE,
            new Runnable() {
              @Override
              public void run() {
                FindBar.this.find.replace();
              }
            });
    replaceAllButton =
        button(
            replaceRow,
            UiText.BUTTON_REPLACE_ALL,
            new Runnable() {
              @Override
              public void run() {
                FindBar.this.find.replaceAll();
              }
            });
    listen();
    find.addListener(listener);
    bar.addListener(
        SWT.Dispose,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            FindBar.this.find.removeListener(listener);
          }
        });
    show(find.state());
  }

  private Composite row(int columns) {
    Composite row = new Composite(bar, SWT.NONE);
    row.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    GridLayout layout = new GridLayout(columns, false);
    layout.marginHeight = 0;
    layout.marginWidth = 0;
    row.setLayout(layout);
    return row;
  }

  private static void label(Composite row, UiText text) {
    Label label = new Label(row, SWT.NONE);
    label.setText(text.text());
    GridData data = new GridData(SWT.FILL, SWT.CENTER, false, false);
    data.widthHint =
        Math.max(
            label.computeSize(SWT.DEFAULT, SWT.DEFAULT).x,
            measure(row, UiText.FIND_REPLACE_LABEL.text()));
    label.setLayoutData(data);
  }

  private static int measure(Composite row, String text) {
    Label probe = new Label(row, SWT.NONE);
    probe.setText(text);
    int width = probe.computeSize(SWT.DEFAULT, SWT.DEFAULT).x;
    probe.dispose();
    return width;
  }

  private static Combo field(Composite row) {
    Combo combo = new Combo(row, SWT.DROP_DOWN);
    GridData data = new GridData(SWT.FILL, SWT.CENTER, false, false);
    data.widthHint = FIELD_WIDTH;
    combo.setLayoutData(data);
    return combo;
  }

  private static Button check(Composite row, UiText label) {
    Button button = new Button(row, SWT.CHECK);
    button.setText(label.text());
    return button;
  }

  private static Button button(Composite row, UiText label, final Runnable action) {
    Button button = new Button(row, SWT.PUSH);
    button.setText(label.text());
    button.addListener(
        SWT.Selection,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            action.run();
          }
        });
    return button;
  }

  private void listen() {
    field.addListener(
        SWT.Modify,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            if (!syncing) {
              find.setText(field.getText());
            }
          }
        });
    replaceField.addListener(
        SWT.Modify,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            if (!syncing) {
              find.setReplacement(replaceField.getText());
            }
          }
        });
    field.addListener(
        SWT.KeyDown,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            if (enter(event) && !field.getListVisible()) {
              event.doit = false;
              if ((event.stateMask & SWT.SHIFT) != 0) {
                find.findPrevious();
              } else {
                find.findNext();
              }
            }
          }
        });
    replaceField.addListener(
        SWT.KeyDown,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            if (enter(event) && !replaceField.getListVisible()) {
              event.doit = false;
              find.replace();
            }
          }
        });
    Listener options =
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            find.setOptions(
                matchCase.getSelection(), wholeWord.getSelection(), regex.getSelection());
          }
        };
    matchCase.addListener(SWT.Selection, options);
    wholeWord.addListener(SWT.Selection, options);
    regex.addListener(SWT.Selection, options);
    Listener escape =
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            if (event.keyCode == SWT.ESC
                && !field.getListVisible()
                && !replaceField.getListVisible()) {
              event.doit = false;
              find.close();
            }
          }
        };
    escapeCloses(bar, escape);
  }

  private static boolean enter(Event event) {
    return event.keyCode == SWT.CR || event.keyCode == SWT.KEYPAD_CR;
  }

  private static void escapeCloses(Control control, Listener escape) {
    control.addListener(SWT.KeyDown, escape);
    if (control instanceof Composite composite) {
      for (Control child : composite.getChildren()) {
        escapeCloses(child, escape);
      }
    }
  }

  /** Focuses the search field and selects its text. */
  void focus() {
    if (!field.isDisposed()) {
      field.setFocus();
      field.setSelection(new Point(0, field.getText().length()));
    }
  }

  Composite control() {
    return bar;
  }

  Combo field() {
    return field;
  }

  Combo replaceField() {
    return replaceField;
  }

  String noticeText() {
    return notice.getText();
  }

  boolean replaceShown() {
    return replaceRow.getVisible();
  }

  private void show(FindState state) {
    if (bar.isDisposed()) {
      return;
    }
    syncing = true;
    try {
      showHistory(field, state.textHistory());
      showHistory(replaceField, state.replaceHistory());
      if (!field.getText().equals(state.text())) {
        field.setText(state.text());
      }
      if (!replaceField.getText().equals(state.replacement())) {
        replaceField.setText(state.replacement());
      }
    } finally {
      syncing = false;
    }
    matchCase.setSelection(state.caseSensitive());
    wholeWord.setSelection(state.wholeWord());
    regex.setSelection(state.regex());
    replaceButton.setEnabled(state.replaceEnabled());
    replaceAllButton.setEnabled(state.replaceEnabled());
    String text = state.running() ? UiText.FIND_SEARCHING.text() : state.notice();
    if (!notice.getText().equals(text)) {
      notice.setText(text.replace("&", "&&"));
      notice.getParent().layout(new Control[] {notice});
    }
    boolean relayout = setShown(replaceRow, state.replace());
    relayout |= setShown(bar, state.open());
    if (relayout) {
      bar.getParent().layout(true, true);
    }
  }

  /** Replaces a field's list, keeping its text and selection: setItems can clear them. */
  private static void showHistory(Combo combo, List<String> history) {
    if (Arrays.asList(combo.getItems()).equals(history)) {
      return;
    }
    String text = combo.getText();
    Point selection = combo.getSelection();
    combo.setItems(history.toArray(new String[0]));
    combo.setText(text);
    combo.setSelection(selection);
  }

  /** Shows or hides a control in its grid; returns true when that changed. */
  private static boolean setShown(Control control, boolean shown) {
    GridData data = (GridData) control.getLayoutData();
    if (data.exclude != shown) {
      return false;
    }
    data.exclude = !shown;
    control.setVisible(shown);
    return true;
  }
}
