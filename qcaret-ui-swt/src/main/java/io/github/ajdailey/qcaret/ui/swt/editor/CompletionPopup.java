package io.github.ajdailey.qcaret.ui.swt.editor;

import io.github.ajdailey.qcaret.app.view.EditorInput;
import java.util.List;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableItem;

/**
 * The word completion list: a borderless shell that never takes focus, below a caret, holding a
 * virtual one-column table that creates only the rows it shows, as wide as the longest word in the
 * monospace editor font; the editor keeps the keyboard. A double click or Enter on a row goes to
 * {@link EditorInput#completionChosen}. Each {@link #show} with no words disposes the shell, so it
 * holds no handle while hidden.
 */
final class CompletionPopup {
  /** Rows shown at most before the table scrolls. */
  static final int VISIBLE_ROWS = 10;

  private final StyledText text;
  private final EditorInput input;
  private Shell shell;
  private Table table;
  private List<String> shown = List.of();

  CompletionPopup(StyledText text, EditorInput input) {
    this.text = text;
    this.input = input;
  }

  /**
   * Shows words below a point of the editor, or hides the list.
   *
   * @param words the words; empty hides the list
   * @param selected the selected row
   * @param below the editor-relative point the list's top left corner goes under
   * @param lineHeight the editor's line height
   * @param font the editor font
   */
  void show(List<String> words, int selected, Point below, int lineHeight, Font font) {
    if (words.isEmpty() || text.isDisposed()) {
      dispose();
      return;
    }
    if (shell == null) {
      shell = new Shell(text.getShell(), SWT.ON_TOP | SWT.TOOL | SWT.NO_FOCUS | SWT.NO_TRIM);
      shell.setLayout(new FillLayout());
      table =
          new Table(
              shell, SWT.SINGLE | SWT.FULL_SELECTION | SWT.V_SCROLL | SWT.NO_FOCUS | SWT.VIRTUAL);
      table.addListener(
          SWT.SetData,
          new Listener() {
            @Override
            public void handleEvent(Event event) {
              TableItem item = (TableItem) event.item;
              int index = table.indexOf(item);
              if (index >= 0 && index < shown.size()) {
                item.setText(shown.get(index));
              }
            }
          });
      table.addListener(
          SWT.DefaultSelection,
          new Listener() {
            @Override
            public void handleEvent(Event event) {
              int index = table.getSelectionIndex();
              if (index >= 0) {
                input.completionChosen(index);
              }
            }
          });
    }
    table.setFont(font);
    if (!words.equals(shown)) {
      shown = List.copyOf(words);
      table.clearAll();
      table.setItemCount(shown.size());
    }
    table.setSelection(selected);
    table.showSelection();
    String longest = "";
    for (String word : shown) {
      if (word.length() > longest.length()) {
        longest = word;
      }
    }
    int widest;
    GC gc = new GC(table);
    try {
      gc.setFont(font);
      widest = gc.textExtent(longest).x;
    } finally {
      gc.dispose();
    }
    int rows = Math.min(VISIBLE_ROWS, words.size());
    int height = rows * table.getItemHeight() + 2 * table.getBorderWidth() + 4;
    int width = Math.max(120, widest + 40);
    Point at = text.toDisplay(below.x, below.y + lineHeight);
    shell.setBounds(at.x, at.y, width, height);
    if (!shell.isVisible()) {
      shell.setVisible(true);
    }
  }

  /**
   * Reports whether the list shows.
   *
   * @return true while shown
   */
  boolean visible() {
    return shell != null && !shell.isDisposed() && shell.isVisible();
  }

  /**
   * Returns the shown words.
   *
   * @return the words, empty when hidden
   */
  List<String> words() {
    return visible() ? shown : List.of();
  }

  /**
   * Returns the list's shell.
   *
   * @return the shell, or null while hidden
   */
  Shell shell() {
    return shell;
  }

  /** Disposes the shell. */
  void dispose() {
    if (shell != null && !shell.isDisposed()) {
      shell.dispose();
    }
    shell = null;
    table = null;
    shown = List.of();
  }
}
