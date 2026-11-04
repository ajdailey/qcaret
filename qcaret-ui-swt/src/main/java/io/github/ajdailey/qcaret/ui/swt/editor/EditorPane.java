package io.github.ajdailey.qcaret.ui.swt.editor;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.command.KeyStroke;
import io.github.ajdailey.qcaret.app.command.Keymap;
import io.github.ajdailey.qcaret.app.editor.ColumnMetrics;
import io.github.ajdailey.qcaret.app.editor.DisplayProjection;
import io.github.ajdailey.qcaret.app.theme.Palette;
import io.github.ajdailey.qcaret.app.theme.Rgb;
import io.github.ajdailey.qcaret.app.view.EditorInput;
import io.github.ajdailey.qcaret.core.document.Selection;
import io.github.ajdailey.qcaret.core.document.SelectionListener;
import io.github.ajdailey.qcaret.core.document.SelectionModel;
import io.github.ajdailey.qcaret.core.document.SelectionSet;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CaretEvent;
import org.eclipse.swt.custom.CaretListener;
import org.eclipse.swt.custom.LineBackgroundEvent;
import org.eclipse.swt.custom.LineBackgroundListener;
import org.eclipse.swt.custom.LineStyleEvent;
import org.eclipse.swt.custom.LineStyleListener;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.custom.VerifyKeyListener;
import org.eclipse.swt.events.PaintEvent;
import org.eclipse.swt.events.PaintListener;
import org.eclipse.swt.events.VerifyEvent;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;

/**
 * One editor: a {@link StyledText} over a {@link ContentAdapter}, with a line-number gutter, the
 * current line and the secondary carets and selections painted from the {@link SelectionModel}.
 *
 * <ul>
 *   <li>Input: a {@code VerifyKey} listener runs the {@link Keymap}'s editor bindings through
 *       {@link EditorInput#execute}, types printable chars through {@link EditorInput#type}, and
 *       consumes both; Insert alone is consumed so the widget never enters overwrite mode. Any edit
 *       the widget still makes (word delete, drag and drop, middle-click paste) arrives as {@code
 *       Verify}, is canceled and goes to {@link EditorInput#replaceFromView}. Navigation keys and
 *       the mouse move the widget's caret natively; each move is sent to the model.
 *   <li>Selections: the widget holds only the model's primary selection, set again after every
 *       model change and every display change. An Alt+click that adds a widget selection is sent to
 *       the model, which then owns it.
 *   <li>Painting: the gutter is drawn in the widget's left margin by a paint listener, its digits
 *       placed by {@link ColumnMetrics}. Secondary carets sit at {@link
 *       StyledText#getLocationAtOffset} and secondary selections are line style backgrounds, so
 *       both follow the widget's measured glyph positions on any script.
 *   <li>Word wrap: the width in columns that fits the text area goes to {@link
 *       EditorInput#viewColumnsChanged} on every resize and font change. A double click at or past
 *       a wrapped row's right edge selects the row's last word.
 *   <li>Font: {@link #setFont} changes the font in place and measures it again; the widget, its
 *       caret, scroll position and focus stay.
 * </ul>
 */
public final class EditorPane {
  /** Pixels between the line numbers and the gutter's edges. */
  static final int GUTTER_PADDING = 6;

  /** Pixels between the gutter and the text. */
  static final int GUTTER_GAP = 4;

  /** Width of a painted secondary caret. */
  static final int CARET_WIDTH = 2;

  private final StyledText text;
  private final ContentAdapter content;
  private final DisplayProjection projection;
  private final SelectionModel selections;
  private final EditorInput input;
  private final Keymap keymap;
  private Font font;
  private final SelectionListener modelListener;
  private ColumnMetrics metrics;
  private int gutterWidth;
  private int gutterDigits;
  private Color gutter;
  private Color lineNumbers;
  private Color currentLine;
  private Color selectionBack;
  private Color selectionFore;
  private Color caret;
  private boolean syncing;

  /**
   * Creates the editor.
   *
   * @param parent the tab folder
   * @param projection the display text
   * @param selections the model's selections for this view
   * @param input receives the input
   * @param keymap the editor's key bindings
   * @param font the editor font, owned by the caller
   * @param palette the colors
   */
  public EditorPane(
      Composite parent,
      DisplayProjection projection,
      SelectionModel selections,
      EditorInput input,
      Keymap keymap,
      Font font,
      Palette palette) {
    this.projection = projection;
    this.selections = selections;
    this.input = input;
    this.keymap = keymap;
    this.font = font;
    text = new StyledText(parent, SWT.MULTI | SWT.H_SCROLL | SWT.V_SCROLL);
    // Font, tabs, margins and colors each reset the widget's per-line cache, O(lines) through a
    // TreeSet: set them while the widget still holds its empty default content.
    text.setFont(font);
    text.setTabs(DisplayProjection.TAB_WIDTH);
    measure();
    setPalette(palette);
    content = new ContentAdapter(projection, input);
    text.setContent(content);
    // The widget moves its own caret while it applies a change; those moves are not the user's.
    content.onChange(
        new Runnable() {
          @Override
          public void run() {
            syncing = true;
          }
        },
        new Runnable() {
          @Override
          public void run() {
            syncing = false;
            updateGutter();
            syncWidget();
          }
        });
    modelListener =
        new SelectionListener() {
          @Override
          public void selectionsChanged(SelectionModel model) {
            syncWidget();
          }
        };
    selections.addListener(modelListener);
    listen();
    syncWidget();
  }

  /**
   * Returns the widget.
   *
   * @return the text widget
   */
  public StyledText text() {
    return text;
  }

  /**
   * Returns the gutter's width.
   *
   * @return pixels left of the text area that hold the line numbers
   */
  public int gutterWidth() {
    return gutterWidth;
  }

  /**
   * Returns the column metrics of the editor font.
   *
   * @return the metrics
   */
  public ColumnMetrics metrics() {
    return metrics;
  }

  /**
   * Changes the font in place: the widget keeps its content, caret and scroll position, and the
   * gutter and wrap width follow the new advance. Costs O(rows) in the widget.
   *
   * @param next the font, owned by the caller
   */
  public void setFont(Font next) {
    if (text.isDisposed() || next == font) {
      return;
    }
    font = next;
    text.setFont(next);
    measure();
    text.redraw();
  }

  /** Scrolls so the primary caret shows. */
  public void showCaret() {
    if (!text.isDisposed()) {
      text.showSelection();
    }
  }

  /** Focuses the widget. */
  public void focus() {
    if (!text.isDisposed()) {
      text.setFocus();
    }
  }

  /** Disposes the widget and stops following the model. */
  public void dispose() {
    selections.removeListener(modelListener);
    content.dispose();
    if (!text.isDisposed()) {
      text.dispose();
    }
  }

  /**
   * Applies a palette. A changed text or background color costs O(rows) in the widget.
   *
   * @param palette the colors
   */
  public void setPalette(Palette palette) {
    Color foreground = color(palette.text());
    Color background = color(palette.background());
    if (!foreground.equals(text.getForeground())) {
      text.setForeground(foreground);
    }
    if (!background.equals(text.getBackground())) {
      text.setBackground(background);
    }
    selectionBack = color(palette.selection());
    selectionFore = color(palette.selectionText());
    text.setSelectionBackground(selectionBack);
    text.setSelectionForeground(selectionFore);
    gutter = color(palette.gutter());
    lineNumbers = color(palette.lineNumbers());
    currentLine = color(palette.currentLine());
    caret = color(palette.text());
    text.redraw();
  }

  private static Color color(Rgb rgb) {
    return new Color(rgb.red(), rgb.green(), rgb.blue());
  }

  /** Measures the font's column advance and resizes the gutter. */
  private void measure() {
    GC gc = new GC(text);
    try {
      gc.setFont(font);
      metrics = ColumnMetrics.fromSample(gc.textExtent(ColumnMetrics.sample()).x);
    } finally {
      gc.dispose();
    }
    gutterDigits = 0;
    updateGutter();
  }

  private void updateGutter() {
    int digits = ColumnMetrics.gutterDigits(projection.lineCount());
    if (digits != gutterDigits) {
      gutterDigits = digits;
      gutterWidth = metrics.x(digits) + 2 * GUTTER_PADDING;
      text.setLeftMargin(gutterWidth + GUTTER_GAP);
      reportColumns();
      text.redraw();
    }
  }

  private void reportColumns() {
    int width =
        text.getClientArea().width - text.getLeftMargin() - text.getRightMargin() - CARET_WIDTH;
    if (width > 0) {
      input.viewColumnsChanged(metrics.columnsIn(width));
    }
  }

  /** Sets the widget's selection to the model's primary and repaints the secondary ones. */
  private void syncWidget() {
    if (text.isDisposed()) {
      return;
    }
    Selection primary = selections.selections().primary();
    int anchor = projection.toDisplay(primary.anchor());
    int caretOffset = projection.toDisplay(primary.caret());
    syncing = true;
    try {
      text.setSelectionRange(anchor, caretOffset - anchor);
    } finally {
      syncing = false;
    }
    text.redraw();
  }

  /** Sends the widget's selections to the model after the user moved them. */
  private void fromWidget() {
    if (syncing || text.isDisposed()) {
      return;
    }
    int[] ranges = text.getSelectionRanges();
    int caretOffset = text.getCaretOffset();
    if (ranges.length == 2) {
      int start = ranges[0];
      int end = start + ranges[1];
      input.selectFromView(caretOffset == start ? end : start, caretOffset);
      return;
    }
    int[] pairs = new int[ranges.length];
    int primary = 0;
    for (int i = 0; i < ranges.length; i += 2) {
      int start = ranges[i];
      int end = start + ranges[i + 1];
      boolean caretAtStart = caretOffset == start;
      pairs[i] = caretAtStart ? end : start;
      pairs[i + 1] = caretAtStart ? start : end;
      if (caretOffset == start || caretOffset == end) {
        primary = i / 2;
      }
    }
    input.selectionsFromView(pairs, primary);
  }

  private void listen() {
    text.addVerifyKeyListener(
        new VerifyKeyListener() {
          @Override
          public void verifyKey(VerifyEvent event) {
            event.doit = !route(event.character, event.keyCode, event.stateMask);
          }
        });
    text.addListener(
        SWT.Verify,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            event.doit = false;
            input.replaceFromView(event.start, event.end, event.text);
          }
        });
    text.addCaretListener(
        new CaretListener() {
          @Override
          public void caretMoved(CaretEvent event) {
            fromWidget();
          }
        });
    text.addListener(
        SWT.Selection,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            fromWidget();
          }
        });
    text.addListener(
        SWT.MouseDoubleClick,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            if (event.button == 1) {
              wordAtWrappedRowEnd(event.x, event.y);
            }
          }
        });
    text.addListener(
        SWT.Resize,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            reportColumns();
          }
        });
    text.addListener(
        SWT.ZoomChanged,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            measure();
          }
        });
    text.addLineBackgroundListener(
        new LineBackgroundListener() {
          @Override
          public void lineGetBackground(LineBackgroundEvent event) {
            Selection primary = selections.selections().primary();
            int row = projection.rowAt(event.lineOffset);
            if (projection.lineOfRow(row) == projection.lineAt(primary.caret())) {
              event.lineBackground = currentLine;
            }
          }
        });
    text.addLineStyleListener(
        new LineStyleListener() {
          @Override
          public void lineGetStyle(LineStyleEvent event) {
            event.styles = secondaryStyles(event.lineOffset, event.lineText.length());
          }
        });
    text.addPaintListener(
        new PaintListener() {
          @Override
          public void paintControl(PaintEvent event) {
            paintGutter(event.gc, event.y, event.height);
            paintCarets(event.gc);
          }
        });
  }

  /**
   * Selects the last word of a wrapped row when a double click lands at or past its right edge. The
   * widget alone takes the row's trailing space and virtual break there, since it sees the row as a
   * line.
   */
  private void wordAtWrappedRowEnd(int x, int y) {
    int row = text.getLineIndex(y);
    if (row + 1 >= projection.rowCount()
        || projection.lineOfRow(row + 1) != projection.lineOfRow(row)) {
      return;
    }
    String rowText = projection.rowText(row);
    int rowStart = projection.rowStart(row);
    if (x < text.getLocationAtOffset(rowStart + rowText.length()).x - 1) {
      return;
    }
    int end = rowText.length();
    while (end > 0 && Character.isWhitespace(rowText.charAt(end - 1))) {
      end--;
    }
    int start = end;
    while (start > 0
        && (Character.isLetterOrDigit(rowText.charAt(start - 1))
            || rowText.charAt(start - 1) == '_')) {
      start--;
    }
    if (start < end) {
      input.selectFromView(rowStart + start, rowStart + end);
    }
  }

  /**
   * Runs an editing key; returns true when it was one, so the widget must not handle it.
   *
   * @param character the key's char, or 0
   * @param keyCode the key code
   * @param stateMask the modifiers
   * @return true when consumed
   */
  boolean route(char character, int keyCode, int stateMask) {
    int mods = stateMask & (SWT.CTRL | SWT.ALT | SWT.COMMAND);
    if (keyCode == SWT.INSERT && stateMask == 0) {
      return true;
    }
    KeyStroke stroke = SwtKeys.stroke(stateMask, keyCode, character);
    CommandId command = stroke == null ? null : keymap.editorCommand(stroke);
    if (command != null) {
      input.execute(command);
      return true;
    }
    boolean typed =
        character >= ' '
            && character != SWT.DEL
            && (mods == 0
                || mods == (SWT.CTRL | SWT.ALT)
                || ("cocoa".equals(SWT.getPlatform()) && mods == SWT.ALT));
    if (typed || (character == '\t' && mods == 0)) {
      input.type(String.valueOf(character));
      return true;
    }
    return false;
  }

  /** Returns the secondary selections' backgrounds within one row. */
  private StyleRange[] secondaryStyles(int rowOffset, int rowLength) {
    SelectionSet set = selections.selections();
    if (set.size() == 1) {
      return new StyleRange[0];
    }
    int from = projection.toModel(rowOffset);
    int to = from + rowLength;
    List<StyleRange> styles = new ArrayList<StyleRange>();
    List<Selection> list = set.selections();
    for (int i = firstEndingAtOrAfter(list, from); i < list.size(); i++) {
      Selection s = list.get(i);
      if (s.start() >= to) {
        break;
      }
      if (i == set.primaryIndex() || s.isEmpty()) {
        continue;
      }
      int start = Math.max(s.start(), from);
      int end = Math.min(s.end(), to);
      if (end > start) {
        styles.add(
            new StyleRange(rowOffset + start - from, end - start, selectionFore, selectionBack));
      }
    }
    return styles.toArray(new StyleRange[0]);
  }

  /** Returns the index of the first selection whose end is at or after {@code offset}. */
  private static int firstEndingAtOrAfter(List<Selection> list, int offset) {
    int lo = 0;
    int hi = list.size();
    while (lo < hi) {
      int mid = (lo + hi) >>> 1;
      if (list.get(mid).end() < offset) {
        lo = mid + 1;
      } else {
        hi = mid;
      }
    }
    return lo;
  }

  private void paintGutter(GC gc, int y, int height) {
    gc.setBackground(gutter);
    gc.fillRectangle(0, y, gutterWidth, height);
    gc.setForeground(lineNumbers);
    gc.setFont(font);
    Rectangle area = text.getClientArea();
    int rows = projection.rowCount();
    for (int row = text.getTopIndex(); row < rows; row++) {
      int top = text.getLinePixel(row);
      if (top > area.height) {
        break;
      }
      if (projection.subRow(row) == 0) {
        String number = Integer.toString(projection.lineOfRow(row) + 1);
        int x = gutterWidth - GUTTER_PADDING - metrics.x(number.length());
        gc.drawString(number, x, top, true);
      }
    }
  }

  private void paintCarets(GC gc) {
    SelectionSet set = selections.selections();
    if (set.size() == 1) {
      return;
    }
    Rectangle area = text.getClientArea();
    int top = text.getTopIndex();
    int bottom = Math.min(projection.rowCount() - 1, text.getLineIndex(area.height));
    int from = projection.toModel(projection.rowStart(top));
    int to = projection.toModel(projection.rowStart(bottom) + projection.rowText(bottom).length());
    int lineHeight = text.getLineHeight();
    gc.setBackground(caret);
    List<Selection> list = set.selections();
    for (int i = firstEndingAtOrAfter(list, from); i < list.size(); i++) {
      Selection s = list.get(i);
      if (s.start() > to) {
        break;
      }
      if (i == set.primaryIndex() || s.caret() < from || s.caret() > to) {
        continue;
      }
      Point at = text.getLocationAtOffset(projection.toDisplay(s.caret()));
      if (at.x >= text.getLeftMargin() - CARET_WIDTH) {
        gc.fillRectangle(at.x, at.y, CARET_WIDTH, lineHeight);
      }
    }
  }
}
