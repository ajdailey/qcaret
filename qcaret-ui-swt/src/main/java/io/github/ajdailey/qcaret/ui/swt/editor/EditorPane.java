package io.github.ajdailey.qcaret.ui.swt.editor;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.command.KeyStroke;
import io.github.ajdailey.qcaret.app.command.Keymap;
import io.github.ajdailey.qcaret.app.editor.Bookmarks;
import io.github.ajdailey.qcaret.app.editor.ColumnMetrics;
import io.github.ajdailey.qcaret.app.editor.DisplayProjection;
import io.github.ajdailey.qcaret.app.editor.HighlightComposer;
import io.github.ajdailey.qcaret.app.editor.LineChanges;
import io.github.ajdailey.qcaret.app.editor.OverviewMarks;
import io.github.ajdailey.qcaret.app.editor.TokenSource;
import io.github.ajdailey.qcaret.app.theme.Palette;
import io.github.ajdailey.qcaret.app.theme.Rgb;
import io.github.ajdailey.qcaret.app.view.EditorInput;
import io.github.ajdailey.qcaret.app.view.EditorView;
import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import io.github.ajdailey.qcaret.app.view.ViewLayout;
import io.github.ajdailey.qcaret.core.document.Selection;
import io.github.ajdailey.qcaret.core.document.SelectionListener;
import io.github.ajdailey.qcaret.core.document.SelectionModel;
import io.github.ajdailey.qcaret.ui.swt.platform.PlatformTraits;
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
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Caret;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Event;
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
 *       first. Input method composition text goes to {@link EditorInput#preeditFromView}. On every
 *       platform the widget removes the composition when it commits, and the committed text arrives
 *       as key events.
 *   <li>Selections: the widget holds only the model's primary selection, set again after every
 *       model change and every display change. A widget report of that primary, such as after a
 *       resize, keeps the other selections; a plain click leaves one.
 *   <li>Painting: the gutter is drawn in the widget's left margin by a paint listener, its digits
 *       placed by {@link ColumnMetrics} and left out, with their width, after {@link
 *       #setLineNumbersShown}. Its right column shows a fold marker on each line that starts a fold
 *       region, a box with a minus sign, or a plus sign while collapsed; a click there goes to
 *       {@link EditorInput#toggleFoldFromView}. A collapsed region's first line ends in a box with
 *       an ellipsis. Secondary carets in hidden lines are not painted. Secondary carets sit at
 *       {@link StyledText#getLocationAtOffset} and secondary selections are line style backgrounds,
 *       so both follow the widget's measured glyph positions on any script. A block's part past a
 *       line's end and its carets there are painted from the line end's measured position at the
 *       font's fractional advance.
 *   <li>Word wrap: the width in columns that fits the text area goes to {@link
 *       EditorInput#viewColumnsChanged} on every resize and font change. A double click selects the
 *       word by {@link EditorGestures#selectWord}.
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
 *   <li>Right margin: {@link MarginPainter} draws the line in the palette's right margin color
 *       after {@link #setRightMargin}.
 *   <li>Indent guides: {@link IndentGuidePainter} after {@link #setIndentGuides}, before the
 *       whitespace marks.
 *   <li>Modified-line marks: {@link LineChangePainter} in the gap between the gutter and the text
 *       after {@link #setLineChangesShown}, which tells {@link EditorInput#lineChangesShown}. While
 *       a document is too large for marks, the gutter's tooltip says so where no bookmark comment
 *       shows.
 * </ul>
 */
public final class EditorPane implements EditorView {
  /**
   * Control data key: the owner colors this control from the palette, so an appearance change
   * leaves it alone.
   */
  public static final String PALETTE_COLORED = "qcaret.paletteColored";

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
  private Color currentLine;
  private Palette palette;
  private final Caret primaryCaret;
  private Image caretImage;
  private final PaneHighlights highlights;
  private final PanePainter painter;
  private int pendingTop = -1;
  private int reportedStart = -1;
  private int reportedEnd = -1;
  private boolean syncing;
  private boolean composing;
  private final EditorLayout layout;
  private final EditorGestures gestures;
  private final UiDispatcher ui;
  private final CompletionPopup completion;
  private boolean lineNumbersShown;
  private boolean busy;
  private boolean windowBusy;
  private final MarginPainter margin = new MarginPainter();
  private final IndentGuidePainter guides = new IndentGuidePainter();
  private final LineChangePainter lineChanges = new LineChangePainter();

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
   * @param lineNumbersShown true to show line numbers in the gutter
   */
  public EditorPane(
      Composite parent,
      DisplayProjection projection,
      SelectionModel selections,
      EditorInput input,
      Keymap keymap,
      Font font,
      Palette palette,
      UiDispatcher ui,
      boolean lineNumbersShown) {
    this.projection = projection;
    this.selections = selections;
    this.input = input;
    this.keymap = keymap;
    this.font = font;
    this.ui = ui;
    this.lineNumbersShown = lineNumbersShown;
    frame = new Composite(parent, SWT.NONE);
    GridLayout frameLayout = new GridLayout(3, false);
    frameLayout.marginWidth = 0;
    frameLayout.marginHeight = 0;
    frameLayout.horizontalSpacing = 0;
    frame.setLayout(frameLayout);
    text = new StyledText(frame, SWT.MULTI | SWT.H_SCROLL);
    text.setData(PALETTE_COLORED, Boolean.TRUE);
    text.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
    highlights = new PaneHighlights(projection, selections);
    painter = new PanePainter(this, text, projection, selections);
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
    content.onComposition(
        new Runnable() {
          @Override
          public void run() {
            composing = true;
          }
        },
        // A change reports whole rows, so StyledText.handleTextChanged takes a composition past
        // column 0 for one that text before it moved, and shifts the IME's offset off the text.
        // With no composition shown, a commit lands at the primary selection.
        new Runnable() {
          @Override
          public void run() {
            int[] range = selections.document().preeditRange();
            int offset = range != null ? range[0] : selections.selections().primary().start();
            text.getIME().setCompositionOffset(projection.toDisplay(offset));
          }
        });
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
   * gutter and wrap width follow the new advance. A shown completion list takes the font and moves
   * under the caret, so the caller can dispose the old font. Costs O(rows) in the widget.
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
    if (completion.visible()) {
      showCompletions(completion.words(), completion.selected());
    }
  }

  /**
   * Shows or hides the line numbers; the gutter keeps its bookmark and fold columns and resizes.
   * Costs O(rows) in the widget.
   *
   * @param shown true to show them
   */
  public void setLineNumbersShown(boolean shown) {
    if (!text.isDisposed() && shown != lineNumbersShown) {
      lineNumbersShown = shown;
      gutterDigits = 0;
      updateGutter();
    }
  }

  /**
   * Shows the right margin line at a column, or hides it.
   *
   * @param column the column the line sits left of, or 0 to hide it
   */
  public void setRightMargin(int column) {
    if (!text.isDisposed() && margin.setColumn(column)) {
      text.redraw();
    }
  }

  /**
   * Shows or hides the indent guides.
   *
   * @param shown true to show them
   */
  public void setIndentGuides(boolean shown) {
    if (!text.isDisposed() && guides.setShown(shown)) {
      text.redraw();
    }
  }

  /**
   * Returns a row's indent guide positions, for tests.
   *
   * @param row a display row
   * @return x per guide in the widget, empty while the guides are hidden
   */
  public int[] indentGuides(int row) {
    if (!guides.shown()) {
      return new int[0];
    }
    return IndentGuidePainter.guides(text, projection, row, text.getClientArea().width);
  }

  /**
   * Shows or hides the modified-line marks and tells the controller, which computes them only while
   * they show.
   *
   * @param shown true to show them
   */
  public void setLineChangesShown(boolean shown) {
    if (!text.isDisposed() && lineChanges.setShown(shown)) {
      input.lineChangesShown(shown);
      text.redraw();
    }
  }

  @Override
  public void showLineChanges(LineChanges changes) {
    if (!text.isDisposed() && lineChanges.shown()) {
      lineChanges.setChanges(changes);
      text.redraw();
    }
  }

  /**
   * Returns the modified-line marks painted.
   *
   * @return the marks, {@link LineChanges#NONE} while hidden
   */
  public LineChanges lineChanges() {
    return lineChanges.changes();
  }

  /**
   * Returns the modified-line mark column's left edge, for tests.
   *
   * @return pixels from the widget's left edge
   */
  public int lineChangeColumn() {
    return gutterWidth;
  }

  /**
   * Returns the right margin line's x, for tests.
   *
   * @return pixels from the widget's left edge, or -1 without a line
   */
  public int rightMarginX() {
    return margin.x(text, metrics);
  }

  /**
   * Prepares a context menu opened by the pointer; see {@link EditorInput#menuFromView}.
   *
   * @param x the point's x in display coordinates
   * @param y its y
   */
  public void menuAt(int x, int y) {
    Point point = text.toControl(x, y);
    input.menuFromView(gestures.offsetAt(point.x, point.y));
  }

  /**
   * Returns where a context menu opened from the keyboard shows.
   *
   * @return the point below the primary caret, in display coordinates
   */
  public Point menuLocation() {
    Point caretAt = text.getLocationAtOffset(text.getCaretOffset());
    return text.toDisplay(caretAt.x, caretAt.y + text.getLineHeight());
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
    release();
    if (!frame.isDisposed()) {
      frame.dispose();
    }
  }

  /**
   * Stops following the model and leaves the widget to its parent's dispose. The widget's text
   * stops following the document, so the caller keeps the widget hidden until it is disposed.
   */
  public void release() {
    selections.removeListener(modelListener);
    painter.release();
    content.dispose();
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
    text.setSelectionBackground(color(palette.selection()));
    text.setSelectionForeground(color(palette.selectionText()));
    currentLine = color(palette.currentLine());
    highlights.setPalette(palette);
    painter.setPalette(palette);
    margin.setColor(color(palette.rightMargin()));
    guides.setColor(color(palette.indentGuide()));
    lineChanges.setColors(
        color(palette.lineAdded()), color(palette.lineChanged()), color(palette.lineRemoved()));
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

  Font font() {
    return font;
  }

  boolean lineNumbersShown() {
    return lineNumbersShown;
  }

  EditorLayout editorLayout() {
    return layout;
  }

  private void updateGutter() {
    int digits = ColumnMetrics.gutterDigits(projection.lineCount());
    if (digits != gutterDigits) {
      gutterDigits = digits;
      int numbers = lineNumbersShown ? metrics.x(digits) + GUTTER_PADDING : 0;
      gutterWidth = BOOKMARK_COLUMN + numbers + metrics.x(FOLD_COLUMNS);
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
    if (syncing || composing || text.isDisposed()) {
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
              gestures.selectWord(event.x, event.y);
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
    // Runs after the widget's own listener, which collapses its selection onto the composition.
    // The model's selection stays, for the commit to replace; the widget gets it back at the end.
    text.getIME()
        .addListener(
            SWT.ImeComposition,
            new Listener() {
              @Override
              public void handleEvent(Event event) {
                if (event.detail == SWT.COMPOSITION_CHANGED) {
                  composing = false;
                  if (selections.document().preeditRange() == null) {
                    syncWidget();
                  }
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
            event.styles = highlights.styles(event.lineOffset, event.lineText.length());
          }
        });
    Listener tipFollower =
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            if (event.type == SWT.MouseExit) {
              painter.updateToolTip(-1, -1);
            } else {
              painter.updateToolTip(event.x, event.y);
            }
          }
        };
    text.addListener(SWT.MouseMove, tipFollower);
    text.addListener(SWT.MouseExit, tipFollower);
    text.addPaintListener(
        new PaintListener() {
          @Override
          public void paintControl(PaintEvent event) {
            painter.paintGutter(event.gc, event.y, event.height);
            lineChanges.paint(event.gc, text, projection, gutterWidth);
            painter.paintCollapsed(event.gc);
            painter.paintBookmarks(event.gc);
            guides.paint(event.gc, text, projection);
            painter.paintMarks(event.gc);
            margin.paint(event.gc, text, metrics, event.y, event.height);
            if (selections.block() != null) {
              painter.paintBlock(event.gc);
            } else {
              painter.paintCarets(event.gc);
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
                || (PlatformTraits.PLATFORM.typesWithAlt() && mods == SWT.ALT));
    if (typed || (character == '\t' && mods == 0)) {
      input.type(String.valueOf(character));
      return true;
    }
    return false;
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

  // Find matches.

  @Override
  public void showMatches(int[] ranges) {
    highlights.setMatches(ranges);
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
    return highlights.matches();
  }

  // Syntax.

  @Override
  public void setSyntax(TokenSource tokens) {
    highlights.setSyntax(tokens);
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
    return highlights.styles(projection.rowStart(row), projection.rowText(row).length());
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
    highlights.setBrackets(ranges);
    if (!text.isDisposed()) {
      text.redraw();
    }
  }

  @Override
  public void showOccurrences(int[] ranges) {
    highlights.setOccurrences(ranges);
    if (!text.isDisposed()) {
      text.redraw();
    }
  }

  @Override
  public void setBookmarks(Bookmarks value) {
    painter.setBookmarks(value);
  }

  /** Shows the busy cursor while this editor's own operations or its window's are active. */
  @Override
  public void setBusy(boolean busy) {
    this.busy = busy;
    showBusy();
  }

  /**
   * Shows the busy cursor while the window's operations are active, whatever the editor's own
   * state.
   *
   * @param busy true while the window is busy
   */
  public void setWindowBusy(boolean busy) {
    windowBusy = busy;
    showBusy();
  }

  private void showBusy() {
    if (!text.isDisposed()) {
      text.setCursor(
          busy || windowBusy
              ? text.getDisplay().getSystemCursor(PlatformTraits.PLATFORM.busyCursor())
              : null);
    }
  }

  @Override
  public void setMarks(boolean whitespaceMarks, boolean lineEndingMarks) {
    if (painter.setMarks(whitespaceMarks, lineEndingMarks) && !text.isDisposed()) {
      text.redraw();
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
    return highlights.brackets();
  }

  /**
   * Returns the occurrence highlight.
   *
   * @return model offset pairs
   */
  public int[] occurrences() {
    return highlights.occurrences();
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
   * Returns the font the completion list draws with.
   *
   * @return the font, or null while the list is hidden
   */
  public Font completionFont() {
    return completion.font();
  }

  /**
   * Reports whether the whitespace and line-ending marks show.
   *
   * @return {whitespace, line endings}
   */
  public boolean[] marks() {
    return painter.marks();
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
}
