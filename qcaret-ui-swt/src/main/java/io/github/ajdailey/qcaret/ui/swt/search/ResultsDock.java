package io.github.ajdailey.qcaret.ui.swt.search;

import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.theme.Palette;
import io.github.ajdailey.qcaret.app.theme.Rgb;
import io.github.ajdailey.qcaret.app.view.ResultLabel;
import io.github.ajdailey.qcaret.app.view.ResultsInput;
import java.util.Arrays;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.layout.RowLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableItem;

/**
 * The HyperSearch results dock: a row of buttons over the result sets, file groups and matches of a
 * {@link ResultsInput}, shown as an indented, expandable tree in a virtual {@link Table}. Rows load
 * as they show, and expanding or collapsing changes only the row count, so a group of 100,000
 * matches costs its visible rows plus one row-count change (GTK's tree takes seconds to expand
 * 10,000 children; measured). {@link ResultRows} maps rows to paths.
 *
 * <ul>
 *   <li>A set or group row starts with {@code +} while collapsed and {@code -} while expanded; a
 *       click on that mark, Enter, a double click, Right and Left expand and collapse it. Left on a
 *       group or match row selects its parent. Enter and a double click on a match row open it;
 *       expanding a group lets the model check its file.
 *   <li>A match row paints its matched text over the palette's find-match color in the palette's
 *       text color; a row whose source changed shows in the disabled color.
 *   <li>Stop, Rerun, Replace, Pin and Clear act on the set of the selected row, or the newest set;
 *       Rerun File on the selected row's group while its source changed; Clear All on every set;
 *       Hide hides the dock. The selection follows its row across changes.
 * </ul>
 */
public final class ResultsDock {
  /** Spaces before a group row's mark and before a match row. */
  static final String INDENT = "    ";

  /** Rows removed one by one at most; a larger cut replaces the table. */
  static final int REMOVE_ROWS = 2000;

  private final Composite root;
  private Table table;
  private final Button stop;
  private final Button rerun;
  private final Button rerunFile;
  private final Button replace;
  private final Button pin;
  private final Button clear;
  private final Button clearAll;
  private final ResultRows rows = new ResultRows();
  private ResultsInput input;
  private int[] selected = new int[0];
  private Color highlight;
  private Color highlightText;

  /** What a button does. */
  private enum Action {
    STOP,
    RERUN,
    RERUN_FILE,
    REPLACE,
    PIN,
    CLEAR,
    CLEAR_ALL,
    HIDE
  }

  /** A loaded row: its path, its label and the indent and mark before the label. */
  private record Row(int[] path, ResultLabel label, String prefix) {}

  /**
   * Creates the dock.
   *
   * @param parent the window's body
   * @param palette the colors of the highlighted match text
   */
  public ResultsDock(Composite parent, Palette palette) {
    root = new Composite(parent, SWT.NONE);
    GridLayout layout = new GridLayout(1, false);
    layout.marginWidth = 0;
    layout.marginHeight = 0;
    layout.verticalSpacing = 0;
    root.setLayout(layout);
    Composite buttons = new Composite(root, SWT.NONE);
    RowLayout row = new RowLayout(SWT.HORIZONTAL);
    row.marginTop = 2;
    row.marginBottom = 2;
    row.spacing = 4;
    buttons.setLayout(row);
    buttons.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    stop = button(buttons, UiText.RESULTS_STOP, Action.STOP);
    rerun = button(buttons, UiText.RESULTS_RERUN, Action.RERUN);
    rerunFile = button(buttons, UiText.RESULTS_RERUN_FILE, Action.RERUN_FILE);
    replace = button(buttons, UiText.RESULTS_REPLACE, Action.REPLACE);
    pin = button(buttons, UiText.RESULTS_PIN, Action.PIN);
    clear = button(buttons, UiText.RESULTS_CLEAR, Action.CLEAR);
    clearAll = button(buttons, UiText.RESULTS_CLEAR_ALL, Action.CLEAR_ALL);
    button(buttons, UiText.RESULTS_HIDE, Action.HIDE);
    setPalette(palette);
    createTable();
    syncButtons();
  }

  private void createTable() {
    table = new Table(root, SWT.VIRTUAL | SWT.SINGLE | SWT.FULL_SELECTION | SWT.V_SCROLL);
    table.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
    listen();
  }

  private Button button(Composite parent, UiText label, final Action action) {
    Button button = new Button(parent, SWT.PUSH);
    button.setText(label.text());
    button.addListener(
        SWT.Selection,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            act(action);
          }
        });
    return button;
  }

  /**
   * Returns the dock's top control.
   *
   * @return the composite
   */
  public Composite control() {
    return root;
  }

  /**
   * Returns the table, for tests.
   *
   * @return the table
   */
  public Table table() {
    return table;
  }

  /**
   * Returns the row of a path, for tests.
   *
   * @param path a set, group or match path
   * @return the row, or -1 while hidden
   */
  public int row(int... path) {
    return rows.row(path);
  }

  /**
   * Shows a model.
   *
   * @param results the model
   */
  public void setInput(ResultsInput results) {
    if (results != input) {
      input = results;
      selected = new int[0];
      refresh(true);
    }
  }

  /**
   * Sets the colors of the highlighted match text and repaints.
   *
   * @param palette the palette
   */
  public void setPalette(Palette palette) {
    highlight = color(palette.findMatch());
    highlightText = color(palette.text());
    if (table != null && !table.isDisposed()) {
      table.redraw();
    }
  }

  private static Color color(Rgb rgb) {
    return new Color(rgb.red(), rgb.green(), rgb.blue());
  }

  /**
   * Reads the model again and reloads the visible rows. The selection stays on its path; after a
   * structural change a hidden path is deselected.
   *
   * @param structure true when sets, groups or rows changed, not only counts of running sets
   */
  public void refresh(boolean structure) {
    if (table.isDisposed()) {
      return;
    }
    int[] keep = selected;
    rows.update(input);
    int count = rows.count();
    if (count < table.getItemCount() - REMOVE_ROWS) {
      // GTK removes rows one by one, seconds for 100,000; a new table is faster (measured).
      boolean focused = table.isFocusControl();
      table.dispose();
      createTable();
      root.layout(true);
      if (focused) {
        table.setFocus();
      }
    }
    table.setItemCount(count);
    table.clearAll();
    select(structure && rows.row(keep) < 0 ? new int[0] : keep);
    syncButtons();
  }

  /**
   * Shows and selects a match's row, expanding its set and group.
   *
   * @param set the set's index
   * @param file the group's index
   * @param match the match's index
   */
  public void reveal(int set, int file, int match) {
    if (table.isDisposed() || input == null || set >= input.setCount()) {
      return;
    }
    rows.update(input);
    rows.setExpanded(new int[] {set, file}, true);
    selected = new int[] {set, file, match};
    refresh(false);
    table.showSelection();
  }

  private void select(int[] path) {
    selected = path;
    int at = path.length == 0 ? -1 : rows.row(path);
    if (at < 0) {
      table.deselectAll();
    } else {
      table.setSelection(at);
    }
  }

  private void listen() {
    table.addListener(
        SWT.SetData,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            load((TableItem) event.item, event.index);
          }
        });
    table.addListener(
        SWT.Selection,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            int at = table.getSelectionIndex();
            selected = at < 0 ? new int[0] : rows.path(at);
            syncButtons();
          }
        });
    table.addListener(
        SWT.DefaultSelection,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            activate();
          }
        });
    table.addListener(
        SWT.MouseDown,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            if (event.button == 1) {
              clickMark(table.getItem(new Point(event.x, event.y)), event.x);
            }
          }
        });
    table.addListener(
        SWT.KeyDown,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            if (event.keyCode == SWT.ARROW_RIGHT || event.keyCode == SWT.ARROW_LEFT) {
              event.doit = false;
              arrow(event.keyCode == SWT.ARROW_RIGHT);
            }
          }
        });
    table.addListener(
        SWT.EraseItem,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            if (highlighted((TableItem) event.item) != null) {
              event.detail &= ~SWT.FOREGROUND;
            }
          }
        });
    table.addListener(
        SWT.PaintItem,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            TableItem item = (TableItem) event.item;
            Row row = highlighted(item);
            if (row != null) {
              paintMatch(event.gc, item, row, event.y, event.height);
            }
          }
        });
  }

  /** Expands or collapses a set or group row when a click lands on its mark. */
  private void clickMark(TableItem item, int x) {
    if (item == null || !(item.getData() instanceof Row row) || row.path().length == 3) {
      return;
    }
    GC gc = new GC(table);
    try {
      int markEnd = item.getTextBounds(0).x + gc.textExtent(row.prefix()).x;
      if (x < markEnd) {
        toggle(row.path());
      }
    } finally {
      gc.dispose();
    }
  }

  /** Sets a row's text and data from the model. */
  private void load(TableItem item, int index) {
    if (input == null) {
      return;
    }
    int[] path = rows.path(index);
    ResultLabel label;
    String prefix;
    switch (path.length) {
      case 1 -> {
        label = input.set(path[0]);
        prefix = rows.expanded(path) ? "- " : "+ ";
      }
      case 2 -> {
        label = input.file(path[0], path[1]);
        prefix = INDENT + (rows.expanded(path) ? "- " : "+ ");
      }
      case 3 -> {
        label = input.match(path[0], path[1], path[2]);
        prefix = INDENT + INDENT;
      }
      default -> {
        return;
      }
    }
    item.setData(new Row(path, label, prefix));
    item.setText(prefix + label.text());
    item.setForeground(
        label.stale()
            ? table.getDisplay().getSystemColor(SWT.COLOR_WIDGET_DISABLED_FOREGROUND)
            : null);
  }

  /** Returns a loaded match row with highlighted text, or null. */
  private static Row highlighted(TableItem item) {
    if (item.getData() instanceof Row row
        && row.path().length == 3
        && row.label().highlightStart() >= 0) {
      return row;
    }
    return null;
  }

  /** Draws a match row: the text in the row's color, the matched part over the highlight. */
  private void paintMatch(GC gc, TableItem item, Row row, int y, int height) {
    String text = item.getText();
    int shift = row.prefix().length();
    int start = Math.min(shift + row.label().highlightStart(), text.length());
    int end = Math.max(start, Math.min(shift + row.label().highlightEnd(), text.length()));
    int top = y + Math.max(0, (height - gc.getFontMetrics().getHeight()) / 2);
    int left = item.getTextBounds(0).x;
    String before = text.substring(0, start);
    String matched = text.substring(start, end);
    gc.drawText(before, left, top, true);
    int at = left + gc.textExtent(before).x;
    Point size = gc.textExtent(matched);
    gc.setBackground(highlight);
    gc.fillRectangle(at, top, size.x, size.y);
    Color foreground = gc.getForeground();
    gc.setForeground(highlightText);
    gc.drawText(matched, at, top, true);
    gc.setForeground(foreground);
    gc.drawText(text.substring(end), at + size.x, top, true);
  }

  /** Expands or collapses a set or group row and keeps it selected. */
  private void toggle(int[] path) {
    boolean expand = !rows.expanded(path);
    rows.setExpanded(path, expand);
    if (expand && path.length == 2) {
      input.expanded(path[0], path[1]);
    }
    selected = path;
    refresh(false);
  }

  /** Enter or a double click: opens a match, or expands or collapses a group or set. */
  private void activate() {
    int at = table.getSelectionIndex();
    if (input == null || at < 0) {
      return;
    }
    int[] path = rows.path(at);
    if (path.length == 3) {
      input.open(path[0], path[1], path[2]);
    } else if (path.length > 0) {
      toggle(path);
    }
  }

  /** Right expands; Left collapses, or selects the parent row. */
  private void arrow(boolean right) {
    int at = table.getSelectionIndex();
    if (input == null || at < 0) {
      return;
    }
    int[] path = rows.path(at);
    if (path.length == 0) {
      return;
    }
    if (path.length < 3 && right != rows.expanded(path)) {
      toggle(path);
    } else if (!right && path.length > 1) {
      select(Arrays.copyOf(path, path.length - 1));
      table.showSelection();
      syncButtons();
    }
  }

  /** Reports whether the selected row lies in a group whose source changed. */
  private boolean staleGroup() {
    return input != null
        && selected.length >= 2
        && selected[0] < input.setCount()
        && selected[1] < input.fileCount(selected[0])
        && input.file(selected[0], selected[1]).stale();
  }

  /** Returns the index of the selected row's set, or the newest set, or -1 without sets. */
  private int selectedSet() {
    if (input == null || input.setCount() == 0) {
      return -1;
    }
    return selected.length == 0 || selected[0] >= input.setCount() ? 0 : selected[0];
  }

  private void act(Action action) {
    int set = selectedSet();
    if (input == null) {
      return;
    }
    switch (action) {
      case STOP -> {
        if (set >= 0) {
          input.stop(set);
        }
      }
      case RERUN -> {
        if (set >= 0) {
          input.rerun(set);
        }
      }
      case RERUN_FILE -> {
        if (staleGroup()) {
          input.rerunFile(selected[0], selected[1]);
        }
      }
      case REPLACE -> {
        if (set >= 0) {
          input.replace(set);
        }
      }
      case PIN -> {
        if (set >= 0) {
          input.togglePin(set);
        }
      }
      case CLEAR -> {
        if (set >= 0) {
          selected = new int[0];
          input.clear(set);
        }
      }
      case CLEAR_ALL -> {
        selected = new int[0];
        input.clearAll();
      }
      case HIDE -> input.hide();
    }
    syncButtons();
  }

  private void syncButtons() {
    if (root.isDisposed()) {
      return;
    }
    int set = selectedSet();
    boolean any = set >= 0;
    stop.setEnabled(any && input.running(set));
    rerun.setEnabled(any);
    rerunFile.setEnabled(any && !input.running(set) && staleGroup());
    replace.setEnabled(any && !input.running(set));
    pin.setEnabled(any);
    clear.setEnabled(any);
    clearAll.setEnabled(any);
  }

  /**
   * Returns a row's text as shown, for tests.
   *
   * @param row the row
   * @return the text, its indent and mark included
   */
  public String text(int row) {
    return table.getItem(row).getText();
  }

  /**
   * Selects a row and presses Enter on it, for tests.
   *
   * @param row the row
   */
  public void activate(int row) {
    table.setSelection(row);
    table.notifyListeners(SWT.Selection, new Event());
    activate();
  }

  /**
   * Presses a button, for tests.
   *
   * @param label the button's label
   */
  public void press(UiText label) {
    for (Button button : new Button[] {stop, rerun, rerunFile, replace, pin, clear, clearAll}) {
      if (button.getText().equals(label.text())) {
        button.notifyListeners(SWT.Selection, new Event());
      }
    }
    if (label == UiText.RESULTS_HIDE) {
      act(Action.HIDE);
    }
  }
}
