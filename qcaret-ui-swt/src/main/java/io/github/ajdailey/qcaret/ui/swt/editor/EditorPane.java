package io.github.ajdailey.qcaret.ui.swt.editor;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.command.KeyStroke;
import io.github.ajdailey.qcaret.app.command.Keymap;
import io.github.ajdailey.qcaret.app.editor.Bookmarks;
import io.github.ajdailey.qcaret.app.editor.ColumnMetrics;
import io.github.ajdailey.qcaret.app.editor.DisplayProjection;
import io.github.ajdailey.qcaret.app.editor.HighlightComposer;
import io.github.ajdailey.qcaret.app.editor.LineEnds;
import io.github.ajdailey.qcaret.app.editor.OverviewMarks;
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
import io.github.ajdailey.qcaret.core.text.LineEnding;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
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
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Caret;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.IME;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Slider;

/**
 * One editor: a {@link StyledText} over a {@link ContentAdapter}, with a line-number gutter, the
 * current line and the secondary carets and selections painted from the {@link SelectionModel},
 * then the {@link OverviewStrip} and a {@link Slider} as the vertical scroll bar, in one composite
 * ({@link #control()}).
 *
 * <ul>
 *   <li>Vertical scrolling: the widget has no native vertical bar. The slider counts rows: maximum
 *       the row count, thumb the rows that fit, selection the top row; it follows the widget on
 *       each paint and resize and after a strip or wheel scroll, and its own moves set the top row.
 *       The wheel scrolls by its event count and consumes the event.
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
 *       placed by {@link ColumnMetrics}. Its right column shows a fold marker on each line that
 *       starts a fold region, a box with a minus sign, or a plus sign while collapsed; a click
 *       there goes to {@link EditorInput#toggleFoldFromView}. A collapsed region's first line ends
 *       in a box with an ellipsis. Secondary carets in hidden lines are not painted. Secondary
 *       carets sit at {@link StyledText#getLocationAtOffset} and secondary selections are line
 *       style backgrounds, so both follow the widget's measured glyph positions on any script. A
 *       block's part past a line's end and its carets there are painted from the line end's
 *       measured position at the font's fractional advance.
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
 *       ({@link #setSyntax}), the find matches ({@link #showMatches}), the bracket pair ({@link
 *       #showBrackets}), the word occurrences ({@link #showOccurrences}) and the secondary
 *       selections; each run takes its token role's color and font style and its background's color
 *       from the palette.
 *   <li>Bookmarks: in the gutter's bookmark column, a bar on each bookmarked line's first row in
 *       the caret color; a commented bookmark is a flag (the bar and a square at its top right) in
 *       the comment color, and its comment shows as the tooltip over the gutter left of the fold
 *       markers. Repainted when the {@link Bookmarks} change.
 *   <li>Marks: in the whitespace color, a dot centered in each space, an arrow across each tab, and
 *       after each line's last row its break's kind ({@code CRLF}, {@code LF}, {@code CR}), each
 *       placed from the widget's measured offsets.
 *   <li>Completion: {@link CompletionPopup} below the primary caret; it closes when the editor
 *       loses focus.
 * </ul>
 */
public final class EditorPane implements EditorView {
  /** Pixels left of the line numbers, holding the bookmark marks. */
  public static final int BOOKMARK_COLUMN = 14;

  /** Pixels from the gutter's left edge to the bookmark bar's right edge. */
  public static final int BOOKMARK_BAR = 5;

  /** Pixels between the line numbers and the fold marker column. */
  static final int GUTTER_PADDING = 6;

  /** Pixels between the gutter and the text. */
  static final int GUTTER_GAP = 4;

  /** Columns of the fold marker column, right of the line numbers. */
  static final int FOLD_COLUMNS = 2;

  /** Width of the primary caret and of a painted secondary caret. */
  public static final int CARET_WIDTH = 2;

  private final Composite frame;
  private final StyledText text;
  private final OverviewStrip strip;
  private final Slider vertical;
  private int[] sliderValues = new int[0];
  private final ContentAdapter content;
  private final DisplayProjection projection;
  private final SelectionModel selections;
  private final EditorInput input;
  private Keymap keymap;
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
  private Color bookmarkComment;
  private Color findMatch;
  private Color[] roleColors = new Color[0];
  private int[] roleFontStyles = new int[0];
  private boolean[] roleUnderlines = new boolean[0];
  private Color occurrenceBack;
  private Color bracketBack;
  private Color whitespace;
  private Palette palette;
  private final Caret primaryCaret;
  private Image caretImage;
  private final HighlightComposer composer = new HighlightComposer();
  private int pendingTop = -1;
  private int reportedStart = -1;
  private int reportedEnd = -1;
  private boolean syncing;
  private final EditorLayout layout;
  private final EditorGestures gestures;
  private final UiDispatcher ui;
  private final CompletionPopup completion;
  private final Runnable bookmarksChanged =
      new Runnable() {
        @Override
        public void run() {
          if (!text.isDisposed()) {
            text.redraw();
          }
        }
      };
  private Bookmarks bookmarks;
  private boolean markWhitespace;
  private boolean markLineEndings;

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
    this.ui = ui;
    frame = new Composite(parent, SWT.NONE);
    GridLayout frameLayout = new GridLayout(3, false);
    frameLayout.marginWidth = 0;
    frameLayout.marginHeight = 0;
    frameLayout.horizontalSpacing = 0;
    frame.setLayout(frameLayout);
    text = new StyledText(frame, SWT.MULTI | SWT.H_SCROLL);
    text.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
    strip =
        new OverviewStrip(
            frame,
            text,
            projection,
            input,
            palette,
            new Runnable() {
              @Override
              public void run() {
                syncScrollBar();
              }
            });
    GridData stripData = new GridData(SWT.FILL, SWT.FILL, false, true);
    stripData.widthHint = OverviewStrip.WIDTH;
    strip.control().setLayoutData(stripData);
    vertical = new Slider(frame, SWT.VERTICAL);
    vertical.setLayoutData(new GridData(SWT.FILL, SWT.FILL, false, true));
    completion = new CompletionPopup(text, input);
    primaryCaret = new Caret(text, SWT.NONE);
    text.setCaret(primaryCaret);
    text.addListener(
        SWT.Dispose,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            completion.dispose();
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
    gestures.setFoldColumn(foldColumn(), gutterWidth);
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
   * Returns the composite holding the text, the overview strip and the vertical scroll bar; lay out
   * the editor through it.
   *
   * @return the editor's top control
   */
  public Composite control() {
    return frame;
  }

  /**
   * Returns the vertical scroll bar.
   *
   * @return the slider beside the overview strip
   */
  public Slider verticalBar() {
    return vertical;
  }

  /**
   * Returns the overview strip's canvas.
   *
   * @return the canvas
   */
  public Canvas overviewStrip() {
    return strip.control();
  }

  /**
   * Returns the overview strip's marks.
   *
   * @return the marks shown
   */
  public OverviewMarks overviewMarks() {
    return strip.marks();
  }

  /**
   * Returns the overview strip's colors, for tests.
   *
   * @return background, frame, find mark, HyperSearch mark, bookmark
   */
  public Color[] overviewColors() {
    return strip.colors();
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
   * Replaces the key bindings the editor dispatches.
   *
   * @param next the bindings
   */
  public void setKeymap(Keymap next) {
    keymap = next;
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

  /**
   * Scrolls so a row is the first visible one; a view without a size yet does so on its first
   * resize.
   *
   * @param row the display row
   */
  public void scrollToRow(int row) {
    if (text.isDisposed()) {
      return;
    }
    if (text.getClientArea().height > 0) {
      pendingTop = -1;
      text.setTopIndex(row);
      syncScrollBar();
    } else {
      pendingTop = row;
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
    if (bookmarks != null) {
      bookmarks.removeListener(bookmarksChanged);
    }
    content.dispose();
    if (!frame.isDisposed()) {
      frame.dispose();
    }
  }

  @Override
  public int topRow() {
    return text.getTopIndex();
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
    bookmarkComment = color(palette.bookmarkComment());
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
    occurrenceBack = color(palette.occurrence());
    bracketBack = color(palette.bracket());
    whitespace = color(palette.whitespace());
    if (strip != null) {
      strip.setPalette(palette);
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

  /**
   * Returns the fold marker column's left edge.
   *
   * @return pixels from the widget's left edge
   */
  public int foldColumn() {
    return gutterWidth - metrics.x(FOLD_COLUMNS);
  }

  private void updateGutter() {
    int digits = ColumnMetrics.gutterDigits(projection.lineCount());
    if (digits != gutterDigits) {
      gutterDigits = digits;
      gutterWidth = BOOKMARK_COLUMN + metrics.x(digits) + GUTTER_PADDING + metrics.x(FOLD_COLUMNS);
      if (gestures != null) {
        gestures.setFoldColumn(foldColumn(), gutterWidth);
      }
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
            if (pendingTop >= 0 && text.getClientArea().height > 0) {
              scrollToRow(pendingTop);
            }
            syncScrollBar();
          }
        });
    // The text has no native vertical bar: the wheel scrolls it here.
    text.addListener(
        SWT.MouseVerticalWheel,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            if (event.doit && event.count != 0) {
              event.doit = false;
              text.setTopIndex(Math.max(0, text.getTopIndex() - event.count));
              syncScrollBar();
            }
          }
        });
    vertical.addListener(
        SWT.Selection,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            if (!text.isDisposed() && text.getTopIndex() != vertical.getSelection()) {
              text.setTopIndex(vertical.getSelection());
            }
            if (vertical.isFocusControl()) {
              text.setFocus();
            }
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
    text.addListener(
        SWT.FocusOut,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            closeCompletionUnlessFocused();
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
    Listener tipFollower =
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            if (event.type == SWT.MouseExit) {
              updateToolTip(-1, -1);
            } else {
              updateToolTip(event.x, event.y);
            }
          }
        };
    text.addListener(SWT.MouseMove, tipFollower);
    text.addListener(SWT.MouseExit, tipFollower);
    text.addPaintListener(
        new PaintListener() {
          @Override
          public void paintControl(PaintEvent event) {
            paintGutter(event.gc, event.y, event.height);
            paintCollapsed(event.gc);
            paintBookmarks(event.gc);
            paintMarks(event.gc);
            if (selections.block() != null) {
              paintBlock(event.gc);
            } else {
              paintCarets(event.gc);
            }
            reportViewport();
            syncScrollBar();
            if (text.getCharCount() > 0) {
              FirstTextPaint.painted();
            }
          }
        });
  }

  /**
   * Sets the slider from the widget's rows: one slider unit per row, the thumb the rows that fit.
   * The strip repaints its visible-range lines when the top row or the row count changes.
   */
  private void syncScrollBar() {
    if (text.isDisposed()) {
      return;
    }
    int rows = projection.rowCount();
    int visible = Math.max(1, text.getClientArea().height / Math.max(1, text.getLineHeight()));
    int thumb = Math.max(1, Math.min(visible, rows));
    int top = text.getTopIndex();
    int[] values = {top, rows, thumb};
    if (Arrays.equals(values, sliderValues)) {
      return;
    }
    sliderValues = values;
    vertical.setValues(top, 0, Math.max(rows, 1), thumb, 1, Math.max(1, visible - 1));
    vertical.setEnabled(rows > visible);
    strip.control().redraw();
  }

  // Overview strip.

  @Override
  public void showOverview(OverviewMarks marks) {
    strip.setMarks(marks);
  }

  @Override
  public void setOverviewShown(boolean shown) {
    Canvas canvas = strip.control();
    GridData data = (GridData) canvas.getLayoutData();
    if (data.exclude != !shown) {
      data.exclude = !shown;
      canvas.setVisible(shown);
      frame.layout(true);
    }
    strip.report();
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
    Color[] backgrounds = {selectionBack, bracketBack, findMatch, occurrenceBack};
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
        style.background = backgrounds[background];
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
        int line = projection.lineOfRow(row);
        String number = Integer.toString(line + 1);
        int x = foldColumn() - GUTTER_PADDING - metrics.x(number.length());
        gc.drawString(number, x, top, true);
        if (projection.foldEnd(line) >= 0) {
          paintFoldMarker(gc, top, projection.collapsed(line));
        }
      }
    }
  }

  /**
   * Draws a fold marker box in the gutter's fold column: a minus sign, or a plus when collapsed.
   */
  private void paintFoldMarker(GC gc, int top, boolean collapsed) {
    int lineHeight = text.getLineHeight();
    int size = Math.max(5, Math.min(metrics.x(FOLD_COLUMNS) - 4, lineHeight - 4)) | 1;
    int x = foldColumn() + (metrics.x(FOLD_COLUMNS) - size) / 2;
    int y = top + (lineHeight - size) / 2;
    int middle = size / 2;
    gc.drawRectangle(x, y, size - 1, size - 1);
    gc.drawLine(x + 2, y + middle, x + size - 3, y + middle);
    if (collapsed) {
      gc.drawLine(x + middle, y + 2, x + middle, y + size - 3);
    }
  }

  /** Draws an ellipsis box after the last row of each visible collapsed region's first line. */
  private void paintCollapsed(GC gc) {
    if (projection.collapsedCount() == 0) {
      return;
    }
    Rectangle area = text.getClientArea();
    int rows = projection.rowCount();
    int lineHeight = text.getLineHeight();
    gc.setForeground(lineNumbers);
    gc.setFont(font);
    for (int row = text.getTopIndex(); row < rows; row++) {
      int top = text.getLinePixel(row);
      if (top > area.height) {
        break;
      }
      int line = projection.lineOfRow(row);
      boolean lastRow = row + 1 >= rows || projection.lineOfRow(row + 1) != line;
      if (!lastRow || !projection.collapsed(line)) {
        continue;
      }
      Point end =
          text.getLocationAtOffset(projection.rowStart(row) + projection.rowText(row).length());
      int x = end.x + metrics.x(1);
      if (x >= text.getLeftMargin()) {
        gc.drawRectangle(x, top + 1, metrics.x(3) + 1, lineHeight - 3);
        gc.drawString("...", x + 1, top, true);
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
      if (i == set.primaryIndex()
          || s.caret() < from
          || s.caret() > to
          || projection.hidden(projection.lineAt(s.caret()))) {
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

  // Bracket and occurrence highlights, bookmarks, marks and the completion list.

  @Override
  public void showBrackets(int[] ranges) {
    composer.setBrackets(ranges);
    if (!text.isDisposed()) {
      text.redraw();
    }
  }

  @Override
  public void showOccurrences(int[] ranges) {
    composer.setOccurrences(ranges);
    if (!text.isDisposed()) {
      text.redraw();
    }
  }

  @Override
  public void setBookmarks(Bookmarks value) {
    if (bookmarks != null) {
      bookmarks.removeListener(bookmarksChanged);
    }
    bookmarks = value;
    value.addListener(bookmarksChanged);
    bookmarksChanged.run();
  }

  @Override
  public void setBusy(boolean busy) {
    if (!text.isDisposed()) {
      text.setCursor(busy ? text.getDisplay().getSystemCursor(SWT.CURSOR_APPSTARTING) : null);
    }
  }

  @Override
  public void setMarks(boolean whitespaceMarks, boolean lineEndingMarks) {
    if (markWhitespace != whitespaceMarks || markLineEndings != lineEndingMarks) {
      markWhitespace = whitespaceMarks;
      markLineEndings = lineEndingMarks;
      if (!text.isDisposed()) {
        text.redraw();
      }
    }
  }

  @Override
  public void showCompletions(List<String> words, int selected) {
    if (text.isDisposed()) {
      return;
    }
    int caretOffset = projection.toDisplay(selections.selections().primary().caret());
    completion.show(
        words, selected, text.getLocationAtOffset(caretOffset), text.getLineHeight(), font);
  }

  /**
   * Returns the bracket highlight.
   *
   * @return model offset pairs
   */
  public int[] brackets() {
    return composer.brackets();
  }

  /**
   * Returns the occurrence highlight.
   *
   * @return model offset pairs
   */
  public int[] occurrences() {
    return composer.occurrences();
  }

  /**
   * Returns the words the completion list shows.
   *
   * @return the words, empty while it is hidden
   */
  public List<String> completionWords() {
    return completion.words();
  }

  /**
   * Reports whether the whitespace and line-ending marks show.
   *
   * @return {whitespace, line endings}
   */
  public boolean[] marks() {
    return new boolean[] {markWhitespace, markLineEndings};
  }

  /** Closes the completion list one UI turn after the editor lost focus, unless it has it again. */
  private void closeCompletionUnlessFocused() {
    if (!completion.visible()) {
      return;
    }
    ui.asyncExec(
        new Runnable() {
          @Override
          public void run() {
            if (!text.isDisposed() && !text.isFocusControl() && completion.visible()) {
              input.execute(CommandId.CANCEL);
            }
          }
        });
  }

  /**
   * Paints the bookmark column on the first row of each visible bookmarked line: a bar in the caret
   * color, or for a commented bookmark the bar and a square at its top right in the comment color.
   */
  private void paintBookmarks(GC gc) {
    if (bookmarks == null) {
      return;
    }
    int[] lines = bookmarks.lines();
    if (lines.length == 0) {
      return;
    }
    int[] commented = bookmarks.commentedLines();
    Rectangle area = text.getClientArea();
    int rows = projection.rowCount();
    int lineHeight = text.getLineHeight();
    for (int row = text.getTopIndex(); row < rows; row++) {
      int top = text.getLinePixel(row);
      if (top > area.height) {
        break;
      }
      int line = projection.lineOfRow(row);
      if (projection.subRow(row) == 0 && Arrays.binarySearch(lines, line) >= 0) {
        int height = Math.max(1, lineHeight - 2);
        if (Arrays.binarySearch(commented, line) >= 0) {
          gc.setBackground(bookmarkComment);
          gc.fillRectangle(
              BOOKMARK_BAR, top + 1, BOOKMARK_COLUMN - BOOKMARK_BAR - 3, Math.min(height, 7));
        } else {
          gc.setBackground(caret);
        }
        gc.fillRectangle(1, top + 1, BOOKMARK_BAR - 1, height);
      }
    }
  }

  /**
   * Shows a commented bookmark's comment as the tooltip while the pointer is over the gutter left
   * of the fold markers on its line, and no tooltip elsewhere.
   */
  private void updateToolTip(int x, int y) {
    String tip = null;
    if (bookmarks != null && x >= 0 && x < foldColumn() && projection.rowCount() > 0) {
      int row = text.getLineIndex(y);
      int top = text.getLinePixel(row);
      if (y >= top && y < top + text.getLineHeight()) {
        tip = bookmarks.comment(projection.lineOfRow(row));
      }
    }
    if (!Objects.equals(tip, text.getToolTipText())) {
      text.setToolTipText(tip);
    }
  }

  /** Paints the whitespace and line-ending marks on the visible rows. */
  private void paintMarks(GC gc) {
    if (!markWhitespace && !markLineEndings) {
      return;
    }
    Rectangle area = text.getClientArea();
    int rows = projection.rowCount();
    int lineHeight = text.getLineHeight();
    gc.setForeground(whitespace);
    gc.setBackground(whitespace);
    gc.setFont(font);
    for (int row = text.getTopIndex(); row < rows; row++) {
      int top = text.getLinePixel(row);
      if (top > area.height) {
        break;
      }
      String rowText = projection.rowText(row);
      int rowStart = projection.rowStart(row);
      if (markWhitespace) {
        paintBlanks(gc, rowText, rowStart, top + lineHeight / 2);
      }
      int line = projection.lineOfRow(row);
      if (markLineEndings && (row + 1 == rows || projection.lineOfRow(row + 1) != line)) {
        LineEnding ending = LineEnds.of(projection.document(), line);
        if (ending != null) {
          Point end = text.getLocationAtOffset(rowStart + rowText.length());
          gc.drawString(ending.name(), end.x + metrics.x(1) / 2, top, true);
        }
      }
    }
  }

  /** Paints a dot in each space and an arrow across each tab of one row. */
  private void paintBlanks(GC gc, String rowText, int rowStart, int middle) {
    int i = 0;
    int length = rowText.length();
    while (i < length) {
      char c = rowText.charAt(i);
      if (c == ' ') {
        int j = i;
        while (j < length && rowText.charAt(j) == ' ') {
          j++;
        }
        int x0 = text.getLocationAtOffset(rowStart + i).x;
        int x1 = text.getLocationAtOffset(rowStart + j).x;
        int n = j - i;
        for (int k = 0; k < n; k++) {
          int x = x0 + (x1 - x0) * (2 * k + 1) / (2 * n);
          gc.fillRectangle(x - 1, middle - 1, 2, 2);
        }
        i = j;
      } else if (c == '\t') {
        int x0 = text.getLocationAtOffset(rowStart + i).x + 2;
        int x1 = text.getLocationAtOffset(rowStart + i + 1).x - 2;
        if (x1 > x0) {
          int head = Math.min(3, x1 - x0);
          gc.drawLine(x0, middle, x1, middle);
          gc.drawLine(x1 - head, middle - head, x1, middle);
          gc.drawLine(x1 - head, middle + head, x1, middle);
        }
        i++;
      } else {
        i++;
      }
    }
  }
}
