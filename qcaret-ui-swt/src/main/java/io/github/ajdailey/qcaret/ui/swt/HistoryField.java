package io.github.ajdailey.qcaret.ui.swt;

import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.ui.swt.platform.AccessibleName;
import io.github.ajdailey.qcaret.ui.swt.platform.DialogSurfaces;
import java.util.List;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;

/**
 * A text field with a history list: a {@link Text}, an arrow button that opens and closes the list,
 * and the list in a borderless shell below the field (above it near the screen's bottom) that never
 * takes focus, so the field keeps the keyboard. Down or Alt+Down opens the list; while it shows, Up
 * and Down move its highlight without changing the text, Enter closes it and puts the highlighted
 * entry in the field, Esc closes it only, and a click on an entry chooses it. A click elsewhere, a
 * focus change, and a move, resize or deactivation of the window close it. Each consumes its key,
 * so key listeners added after this field's see {@code doit} false; while the list shows, Enter and
 * Esc do not traverse, so a dialog neither presses its default button nor closes. Tab skips the
 * arrow. The text's accessible name is the text of the {@link Label} created just before the field
 * in the same parent, without its mnemonic and colon; the arrow's is {@link UiText#FIND_HISTORY}.
 *
 * <p>The list is QCaret's own shell, not a native drop-down: a cocoa {@code NSComboBox} list runs a
 * tracking loop on the UI thread that outlives its window and keeps the process running after a
 * quit with the list open. Confined to the UI thread.
 */
final class HistoryField {
  /** Rows shown at most before the list scrolls. */
  static final int VISIBLE_ROWS = 10;

  private final Composite control;
  private final Text text;
  private final Button arrow;
  private final Listener outside;
  private final SwtUiDispatcher ui;
  private Shell popup;
  private Table table;
  private List<String> items = List.of();

  /**
   * Creates the field.
   *
   * @param parent the parent, laid out by a {@code GridLayout}
   * @param width the text's width hint
   */
  HistoryField(Composite parent, int width) {
    Control[] before = parent.getChildren();
    control = new Composite(parent, SWT.NONE);
    ui = new SwtUiDispatcher(parent.getDisplay());
    GridLayout layout = new GridLayout(2, false);
    layout.marginWidth = 0;
    layout.marginHeight = 0;
    layout.horizontalSpacing = 0;
    control.setLayout(layout);
    control.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, false, false));
    text = new Text(control, SWT.SINGLE | SWT.BORDER);
    GridData data = new GridData(SWT.FILL, SWT.CENTER, true, false);
    data.widthHint = width;
    text.setLayoutData(data);
    arrow = new Button(control, SWT.ARROW | SWT.DOWN);
    arrow.setLayoutData(new GridData(SWT.FILL, SWT.FILL, false, true));
    control.setTabList(new Control[] {text});
    if (before.length > 0 && before[before.length - 1] instanceof Label label) {
      AccessibleName.attachKeepingRole(text, name(label.getText()));
    }
    AccessibleName.attachKeepingRole(arrow, UiText.FIND_HISTORY.text());
    outside =
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            if (event.widget != arrow && event.widget != table) {
              hide();
            }
          }
        };
    listen();
  }

  /** Returns a label's text without its mnemonic marker and trailing colon. */
  static String name(String label) {
    String plain = label.replace("&", "").strip();
    return plain.endsWith(":") ? plain.substring(0, plain.length() - 1).strip() : plain;
  }

  private void listen() {
    text.addListener(
        SWT.Traverse,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            if (listVisible()
                && (event.detail == SWT.TRAVERSE_RETURN || event.detail == SWT.TRAVERSE_ESCAPE)) {
              event.doit = false;
            }
          }
        });
    text.addListener(
        SWT.KeyDown,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            key(event);
          }
        });
    text.addListener(
        SWT.FocusOut,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            hideUnlessPointedAt();
          }
        });
    arrow.addListener(
        SWT.Selection,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            if (listVisible()) {
              hide();
            } else {
              show(-1);
            }
            text.setFocus();
          }
        });
    final Shell shell = control.getShell();
    final Listener close =
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            if (event.type == SWT.Deactivate) {
              hideUnlessPointedAt();
            } else {
              hide();
            }
          }
        };
    shell.addListener(SWT.Move, close);
    shell.addListener(SWT.Resize, close);
    shell.addListener(SWT.Deactivate, close);
    control.addListener(
        SWT.Dispose,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            if (!shell.isDisposed()) {
              shell.removeListener(SWT.Move, close);
              shell.removeListener(SWT.Resize, close);
              shell.removeListener(SWT.Deactivate, close);
            }
            hide();
          }
        });
  }

  private void key(Event event) {
    boolean down = event.keyCode == SWT.ARROW_DOWN;
    if (!listVisible()) {
      if (down && !items.isEmpty()) {
        event.doit = false;
        show(0);
      }
      return;
    }
    switch (event.keyCode) {
      case SWT.ARROW_DOWN, SWT.ARROW_UP -> {
        event.doit = false;
        int row = table.getSelectionIndex() + (down ? 1 : -1);
        if (row >= 0 && row < items.size()) {
          table.setSelection(row);
          table.showSelection();
        }
      }
      case SWT.CR, SWT.KEYPAD_CR -> {
        event.doit = false;
        int row = table.getSelectionIndex();
        if (row >= 0) {
          choose(row);
        } else {
          hide();
        }
      }
      case SWT.ESC -> {
        event.doit = false;
        hide();
      }
      default -> {
        // Typing goes to the field with the list open.
      }
    }
  }

  /**
   * Hides the list on the next turn unless the pointer is over the list or the arrow, the list's
   * shell is active, or the field still has focus in an active window.
   */
  private void hideUnlessPointedAt() {
    ui.asyncExec(
        new Runnable() {
          @Override
          public void run() {
            if (control.isDisposed() || !listVisible()) {
              return;
            }
            Display display = control.getDisplay();
            Control pointed = display.getCursorControl();
            Shell active = display.getActiveShell();
            boolean kept =
                pointed == arrow
                    || pointed == table
                    || active == popup
                    || (text.isFocusControl() && active == control.getShell());
            if (!kept) {
              hide();
            }
          }
        });
  }

  /**
   * Opens the list.
   *
   * @param highlighted the row to highlight, or -1
   */
  void show(int highlighted) {
    if (items.isEmpty() || text.isDisposed()) {
      return;
    }
    Display display = text.getDisplay();
    if (popup == null) {
      popup = new Shell(text.getShell(), SWT.ON_TOP | SWT.TOOL | SWT.NO_FOCUS | SWT.NO_TRIM);
      Dialogs.inheritIcons(popup, text.getShell());
      DialogSurfaces.platform().apply(popup);
      popup.setLayout(new FillLayout());
      table =
          new Table(
              popup, SWT.BORDER | SWT.SINGLE | SWT.FULL_SELECTION | SWT.V_SCROLL | SWT.NO_FOCUS);
      table.setFont(text.getFont());
      table.addListener(
          SWT.Selection,
          new Listener() {
            @Override
            public void handleEvent(Event event) {
              int row = table.getSelectionIndex();
              if (row >= 0) {
                choose(row);
              }
            }
          });
      fill();
      display.addFilter(SWT.MouseDown, outside);
    }
    if (highlighted >= 0 && highlighted < items.size()) {
      table.setSelection(highlighted);
      table.showSelection();
    } else {
      table.deselectAll();
    }
    place();
    if (!popup.isVisible()) {
      popup.setVisible(true);
    }
  }

  private void fill() {
    table.removeAll();
    for (String item : items) {
      new TableItem(table, SWT.NONE).setText(item);
    }
  }

  private void place() {
    int rows = Math.min(VISIBLE_ROWS, items.size());
    int height = rows * table.getItemHeight() + 2 * table.getBorderWidth() + 2;
    int width = Math.max(control.getSize().x, table.computeSize(SWT.DEFAULT, SWT.DEFAULT).x);
    Point below = text.toDisplay(0, text.getSize().y);
    Rectangle screen = text.getMonitor().getClientArea();
    int y =
        below.y + height > screen.y + screen.height ? below.y - text.getSize().y - height : below.y;
    popup.setBounds(below.x, y, width, height);
  }

  /** Closes the list and disposes its shell, so a closed list holds no handle. */
  void hide() {
    if (popup != null) {
      if (!control.isDisposed()) {
        control.getDisplay().removeFilter(SWT.MouseDown, outside);
      }
      if (!popup.isDisposed()) {
        popup.dispose();
      }
    }
    popup = null;
    table = null;
  }

  /**
   * Closes the list and puts an entry in the field, which sends {@code SWT.Modify}.
   *
   * @param row the entry's index
   */
  void choose(int row) {
    String entry = items.get(row);
    hide();
    if (!text.isDisposed()) {
      text.setText(entry);
      text.setSelection(entry.length());
    }
  }

  /**
   * Replaces the entries; the text stays, and an open list shows the new ones.
   *
   * @param entries the entries, newest first
   */
  void setItems(List<String> entries) {
    if (entries.equals(items)) {
      return;
    }
    items = List.copyOf(entries);
    if (listVisible()) {
      if (items.isEmpty()) {
        hide();
      } else {
        fill();
        place();
      }
    }
  }

  /**
   * Returns the entries.
   *
   * @return the entries, newest first
   */
  List<String> items() {
    return items;
  }

  /**
   * Reports whether the list shows.
   *
   * @return true while shown
   */
  boolean listVisible() {
    return popup != null && !popup.isDisposed() && popup.isVisible();
  }

  /**
   * Returns the highlighted row.
   *
   * @return the row, or -1 when none is or the list is closed
   */
  int highlighted() {
    return listVisible() ? table.getSelectionIndex() : -1;
  }

  /**
   * Returns the list's shell.
   *
   * @return the shell, or null while closed
   */
  Shell popup() {
    return popup;
  }

  /**
   * Returns the field and its arrow, for layout.
   *
   * @return the composite
   */
  Composite control() {
    return control;
  }

  /**
   * Returns the text field.
   *
   * @return the field
   */
  Text text() {
    return text;
  }

  /**
   * Returns the arrow button.
   *
   * @return the button
   */
  Button arrow() {
    return arrow;
  }
}
