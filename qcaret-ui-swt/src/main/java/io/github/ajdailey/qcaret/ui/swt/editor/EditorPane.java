package io.github.ajdailey.qcaret.ui.swt.editor;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.command.KeyStroke;
import io.github.ajdailey.qcaret.app.command.Keymap;
import io.github.ajdailey.qcaret.app.editor.ColumnMetrics;
import io.github.ajdailey.qcaret.app.editor.DisplayProjection;
import io.github.ajdailey.qcaret.app.editor.HighlightComposer;
import io.github.ajdailey.qcaret.app.editor.TokenSource;
import io.github.ajdailey.qcaret.app.theme.Palette;
import io.github.ajdailey.qcaret.app.theme.Rgb;
import io.github.ajdailey.qcaret.app.theme.TokenRole;
import io.github.ajdailey.qcaret.app.theme.TokenStyle;
import io.github.ajdailey.qcaret.app.view.EditorInput;
import io.github.ajdailey.qcaret.app.view.EditorView;
import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import io.github.ajdailey.qcaret.app.view.ViewLayout;
import io.github.ajdailey.qcaret.core.document.BlockSelection;
import io.github.ajdailey.qcaret.core.document.Selection;
import io.github.ajdailey.qcaret.core.document.SelectionListener;
import io.github.ajdailey.qcaret.core.document.SelectionModel;
import io.github.ajdailey.qcaret.core.document.SelectionSet;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;
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
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageDataProvider;
import org.eclipse.swt.graphics.PaletteData;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Caret;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.IME;
import org.eclipse.swt.widgets.Listener;

/**
 * One editor: a {@link StyledText} over a {@link ContentAdapter}, with a line-number gutter, the
 * current line and the secondary carets and selections painted from the {@link SelectionModel}.
 *
 * <ul>
 *   <li>Input: a {@code VerifyKey} listener runs the {@link Keymap}'s editor bindings, caret moves
 *       included, through {@link EditorInput#execute}, types printable chars through {@link
 *       EditorInput#type}, and consumes both; Insert alone is consumed so the widget never enters
 *       overwrite mode. Any edit the widget still makes (word delete, drag and drop, middle-click
 *       paste) arrives as {@code Verify}, is canceled and goes to {@link
 *       EditorInput#replaceFromView}. A plain click and drag move the widget's caret natively; each
 *       move is sent to the model. {@link EditorGestures} takes the block and add-caret gestures
 *       first. Input method composition text goes to {@link EditorInput#preeditFromView}, and a
 *       commit reported with the composition to {@link EditorInput#commitFromView}.
 *   <li>Selections: the widget holds only the model's primary selection, set again after every
 *       model change and every display change. A widget report of that primary, such as after a
 *       resize, keeps the other selections; a plain click leaves one.
 *   <li>Painting: the gutter is drawn in the widget's left margin by a paint listener, its digits
 *       placed by {@link ColumnMetrics}. Secondary carets sit at {@link
 *       StyledText#getLocationAtOffset} and secondary selections are line style backgrounds, so
 *       both follow the widget's measured glyph positions on any script. A block's part past a
 *       line's end and its carets there are painted from the line end's measured position at the
 *       font's fractional advance.
 *   <li>Word wrap: the width in columns that fits the text area goes to {@link
 *       EditorInput#viewColumnsChanged} on every resize and font change. A double click at or past
 *       a wrapped row's right edge selects the row's last word.
 *   <li>Primary caret: the widget's caret is an SWT {@link Caret} whose image, {@value
 *       #CARET_WIDTH} px wide and one line high, shows the scheme's caret color over the current
 *       line ({@link CaretInk}). The image is rebuilt on a font change, a zoom change and a palette
 *       change, and disposed with the editor.
 *   <li>Font: {@link #setFont} changes the font in place and measures it again; the widget, its
 *       caret, scroll position and focus stay.
 *   <li>Find: each paint that shows another display range reports it through {@link
 *       EditorInput#viewportChanged}.
 *   <li>Line styles: a {@link HighlightComposer} composes each painted row from the syntax tokens
 *       ({@link #setSyntax}), the find matches ({@link #showMatches}) and the secondary selections;
 *       each run takes its token role's color and font style from the palette.
 * </ul>
 */
public final class EditorPane implements EditorView {
  /** Pixels between the line numbers and the gutter's edges. */
  static final int GUTTER_PADDING = 6;

  /** Pixels between the gutter and the text. */
  static final int GUTTER_GAP = 4;

  /** Width of the primary caret and of a painted secondary caret. */
  public static final int CARET_WIDTH = 2;

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
  private Color findMatch;
  private Color[] roleColors = new Color[0];
  private int[] roleFontStyles = new int[0];
  private boolean[] roleUnderlines = new boolean[0];
  private Palette palette;
  private final Caret primaryCaret;
  private Image caretImage;
  private final HighlightComposer composer = new HighlightComposer();
  private int reportedStart = -1;
  private int reportedEnd = -1;
  private boolean syncing;
  private final EditorLayout layout;
  private final EditorGestures gestures;

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
   * @param ui runs the block drag's timer
   */
  public EditorPane(
      Composite parent,
      DisplayProjection projection,
      SelectionModel selections,
      EditorInput input,
      Keymap keymap,
      Font font,
      Palette palette,
      UiDispatcher ui) {
    this.projection = projection;
    this.selections = selections;
    this.input = input;
    this.keymap = keymap;
    this.font = font;
    text = new StyledText(parent, SWT.MULTI | SWT.H_SCROLL | SWT.V_SCROLL);
    primaryCaret = new Caret(text, SWT.NONE);
    text.setCaret(primaryCaret);
    text.addListener(
        SWT.Dispose,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            // The caret goes first: cocoa reads its image while releasing it.
            if (!primaryCaret.isDisposed()) {
              primaryCaret.dispose();
            }
            if (caretImage != null) {
              caretImage.dispose();
              caretImage = null;
            }
          }
        });
    // Font, tabs, margins and colors each reset the widget's per-line cache, O(lines) through a
    // TreeSet: set them while the widget still holds its empty default content.
    text.setFont(font);
    text.setTabs(projection.tabWidth());
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
    layout =
        new EditorLayout(
            text,
            projection,
            new Supplier<ColumnMetrics>() {
              @Override
              public ColumnMetrics get() {
                return metrics;
              }
            });
    gestures = new EditorGestures(text, projection, input, ui);
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
  @Override
  public void showCaret() {
    if (!text.isDisposed()) {
      text.showSelection();
    }
  }

  /** Focuses the widget. */
  @Override
  public void focus() {
    if (!text.isDisposed()) {
      text.setFocus();
    }
  }

  /** Disposes the widget and the caret image and stops following the model. */
  public void dispose() {
    selections.removeListener(modelListener);
    content.dispose();
    if (!text.isDisposed()) {
      text.dispose();
    }
  }

  /**
   * Returns the primary caret's image.
   *
   * @return the image, null after dispose
   */
  public Image caretImage() {
    return caretImage;
  }

  /**
   * Reports whether a block drag held outside the text area is scrolling.
   *
   * @return true while the drag timer is armed
   */
  public boolean autoscrolling() {
    return gestures.autoscrolling();
  }

  /**
   * Applies a palette. A changed text or background color costs O(rows) in the widget.
   *
   * @param palette the colors
   */
  public void setPalette(Palette palette) {
    this.palette = palette;
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
    caret = color(palette.caret());
    findMatch = color(palette.findMatch());
    TokenRole[] roles = TokenRole.values();
    roleColors = new Color[roles.length];
    roleFontStyles = new int[roles.length];
    roleUnderlines = new boolean[roles.length];
    for (TokenRole role : roles) {
      TokenStyle style = palette.style(role);
      roleColors[role.ordinal()] = color(style.foreground());
      roleFontStyles[role.ordinal()] =
          (style.bold() ? SWT.BOLD : SWT.NORMAL) | (style.italic() ? SWT.ITALIC : SWT.NORMAL);
      roleUnderlines[role.ordinal()] = style.underline();
    }
    updateCaret();
    text.redraw();
  }

  /** Builds the primary caret's image for the line height, zoom and palette. */
  private void updateCaret() {
    if (palette == null || text.isDisposed()) {
      return;
    }
    RGB ink =
        CaretInk.source(rgb(palette.currentLine()), rgb(palette.caret()), CaretInk.platform());
    final int pixel = (ink.red << 16) | (ink.green << 8) | ink.blue;
    final int height = Math.max(1, text.getLineHeight());
    Image next =
        new Image(
            text.getDisplay(),
            new ImageDataProvider() {
              @Override
              public ImageData getImageData(int zoom) {
                ImageData data =
                    new ImageData(
                        Math.max(1, CARET_WIDTH * zoom / 100),
                        Math.max(1, height * zoom / 100),
                        24,
                        new PaletteData(0xff0000, 0xff00, 0xff));
                for (int y = 0; y < data.height; y++) {
                  for (int x = 0; x < data.width; x++) {
                    data.setPixel(x, y, pixel);
                  }
                }
                return data;
              }
            });
    primaryCaret.setImage(next);
    if (caretImage != null) {
      caretImage.dispose();
    }
    caretImage = next;
  }

  private static RGB rgb(Rgb value) {
    return new RGB(value.red(), value.green(), value.blue());
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
    updateCaret();
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

  /**
   * Sends the widget's selections to the model after the user moved them. A report of the primary
   * as the model holds it, such as after a resize, keeps the other selections unless {@code click}.
   */
  private void fromWidget(boolean click) {
    if (syncing || text.isDisposed()) {
      return;
    }
    int[] ranges = text.getSelectionRanges();
    int caretOffset = text.getCaretOffset();
    if (ranges.length == 2) {
      int start = ranges[0];
      int end = start + ranges[1];
      int anchor = caretOffset == start ? end : start;
      Selection primary = selections.selections().primary();
      if (click
          || anchor != projection.toDisplay(primary.anchor())
          || caretOffset != projection.toDisplay(primary.caret())) {
        input.selectFromView(anchor, caretOffset);
      }
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
            fromWidget(false);
          }
        });
    text.addListener(
        SWT.Selection,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            fromWidget(false);
          }
        });
    // Runs after the widget placed its caret: a plain click leaves one selection.
    text.addListener(
        SWT.MouseDown,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            if (event.button == 1 && (event.stateMask & SWT.MODIFIER_MASK) == 0) {
              fromWidget(true);
            }
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
    text.addListener(
        SWT.ImeComposition,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            IME ime = text.getIME();
            if (event.detail == SWT.COMPOSITION_CHANGED
                && ime != null
                && ime.getCommitCount() > 0) {
              input.commitFromView(ime.getCommitCount());
            }
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
            event.styles = styles(event.lineOffset, event.lineText.length());
          }
        });
    text.addPaintListener(
        new PaintListener() {
          @Override
          public void paintControl(PaintEvent event) {
            paintGutter(event.gc, event.y, event.height);
            if (selections.block() != null) {
              paintBlock(event.gc);
            } else {
              paintCarets(event.gc);
            }
            reportViewport();
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

  /** Returns a row's styles from the composer's runs. */
  private StyleRange[] styles(int rowOffset, int rowLength) {
    int from = projection.toModel(rowOffset);
    int line = projection.lineOfRow(projection.rowAt(rowOffset));
    int lineStart = projection.lineStart(line);
    int[] runs =
        composer.compose(line, lineStart, from, from + rowLength, secondaryRanges(from, rowLength));
    StyleRange[] styles = new StyleRange[runs.length / 4];
    int selection = HighlightComposer.Background.SELECTION.ordinal();
    for (int i = 0; i < runs.length; i += 4) {
      int role = runs[i + 2];
      int background = runs[i + 3];
      StyleRange style = new StyleRange();
      style.start = rowOffset + runs[i];
      style.length = runs[i + 1];
      if (role != HighlightComposer.NONE) {
        style.foreground = roleColors[role];
        style.fontStyle = roleFontStyles[role];
        style.underline = roleUnderlines[role];
      }
      if (background == selection) {
        style.foreground = selectionFore;
        style.background = selectionBack;
      } else if (background != HighlightComposer.NONE) {
        style.background = findMatch;
      }
      styles[i / 4] = style;
    }
    return styles;
  }

  /** Returns the secondary selections within one row as model offset pairs. */
  private int[] secondaryRanges(int from, int rowLength) {
    SelectionSet set = selections.selections();
    if (set.size() == 1) {
      return new int[0];
    }
    int to = from + rowLength;
    int[] out = new int[16];
    int size = 0;
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
        if (size + 2 > out.length) {
          out = Arrays.copyOf(out, out.length * 2);
        }
        out[size++] = start;
        out[size++] = end;
      }
    }
    return Arrays.copyOf(out, size);
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

  // Block selection, tab width and layout queries.

  /**
   * Returns the pixel and column queries of this editor.
   *
   * @return the layout
   */
  @Override
  public ViewLayout layout() {
    return layout;
  }

  /**
   * Sets the columns per tab stop. Costs O(rows) in the widget.
   *
   * @param columns from 1 to 16
   */
  @Override
  public void setTabWidth(int columns) {
    if (!text.isDisposed() && text.getTabs() != columns) {
      text.setTabs(columns);
    }
  }

  /**
   * Turns column selection mode on or off.
   *
   * @param on true when a plain drag selects a block
   */
  @Override
  public void setColumnMode(boolean on) {
    gestures.setColumnMode(on);
  }

  /**
   * Paints a block on the visible rows: its part past each short line's end in the selection color,
   * and each row's caret but the widget's own, past the line end at the block's caret column.
   */
  private void paintBlock(GC gc) {
    BlockSelection block = selections.block();
    SelectionSet set = selections.selections();
    Rectangle area = text.getClientArea();
    int top = text.getTopIndex();
    int bottom = Math.min(projection.rowCount() - 1, text.getLineIndex(area.height));
    int from = projection.toModel(projection.rowStart(top));
    int to = projection.toModel(projection.rowStart(bottom) + projection.rowText(bottom).length());
    int left = Math.min(block.anchorColumn(), block.caretColumn());
    int right = Math.max(block.anchorColumn(), block.caretColumn());
    int lineHeight = text.getLineHeight();
    List<Selection> list = set.selections();
    for (int i = firstEndingAtOrAfter(list, from); i < list.size(); i++) {
      Selection s = list.get(i);
      if (s.start() > to) {
        break;
      }
      Point end = text.getLocationAtOffset(projection.toDisplay(s.end()));
      int endColumn = layout.lineColumn(projection.toDisplay(s.end()));
      if (endColumn < right) {
        int x0 = end.x + metrics.x(Math.max(0, left - endColumn));
        int x1 = end.x + metrics.x(right - endColumn);
        gc.setBackground(selectionBack);
        gc.fillRectangle(x0, end.y, x1 - x0, lineHeight);
      }
      int caretColumn = layout.lineColumn(projection.toDisplay(s.caret()));
      boolean virtual = caretColumn < block.caretColumn();
      if (i == set.primaryIndex() && !virtual) {
        continue;
      }
      Point at = text.getLocationAtOffset(projection.toDisplay(s.caret()));
      int x = virtual ? at.x + metrics.x(block.caretColumn() - caretColumn) : at.x;
      if (x >= text.getLeftMargin() - CARET_WIDTH) {
        gc.setBackground(caret);
        gc.fillRectangle(x, at.y, CARET_WIDTH, lineHeight);
      }
    }
  }

  // Find matches.

  @Override
  public void showMatches(int[] ranges) {
    composer.setMatches(ranges);
    if (!text.isDisposed()) {
      text.redraw();
    }
  }

  /**
   * Returns the matches shown.
   *
   * @return model offset pairs
   */
  public int[] matches() {
    return composer.matches();
  }

  // Syntax.

  @Override
  public void setSyntax(TokenSource tokens) {
    composer.setSyntax(tokens);
    redrawHighlights();
  }

  @Override
  public void redrawHighlights() {
    if (!text.isDisposed()) {
      text.redraw();
    }
  }

  /**
   * Returns the styles the widget paints a row with, for tests.
   *
   * @param row the display row
   * @return the row's styles, offsets in the display text
   */
  public StyleRange[] rowStyles(int row) {
    return styles(projection.rowStart(row), projection.rowText(row).length());
  }

  /** Reports the visible display range when it differs from the last one reported. */
  private void reportViewport() {
    int rows = projection.rowCount();
    int top = Math.min(text.getTopIndex(), rows - 1);
    int bottom = Math.min(rows - 1, text.getLineIndex(text.getClientArea().height));
    int start = projection.rowStart(top);
    int end = projection.rowStart(bottom) + projection.rowText(bottom).length();
    if (start != reportedStart || end != reportedEnd) {
      reportedStart = start;
      reportedEnd = end;
      input.viewportChanged(start, end);
    }
  }
}
