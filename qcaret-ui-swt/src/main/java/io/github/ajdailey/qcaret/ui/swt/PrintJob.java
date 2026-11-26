package io.github.ajdailey.qcaret.ui.swt;

import io.github.ajdailey.qcaret.app.editor.MarkdownTableLayout;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.theme.Palette;
import io.github.ajdailey.qcaret.app.theme.Rgb;
import io.github.ajdailey.qcaret.app.theme.TokenStyle;
import io.github.ajdailey.qcaret.app.view.MarkdownImageRequest;
import io.github.ajdailey.qcaret.app.view.MarkdownPreviewContent;
import io.github.ajdailey.qcaret.app.view.MarkdownPrintSheet;
import io.github.ajdailey.qcaret.app.view.PrintTarget;
import io.github.ajdailey.qcaret.core.text.ControlChars;
import io.github.ajdailey.qcaret.ui.swt.editor.ControlBoxes;
import io.github.ajdailey.qcaret.ui.swt.markdown.MarkdownPrinting;
import java.util.function.IntFunction;
import org.eclipse.swt.SWT;
import org.eclipse.swt.SWTError;
import org.eclipse.swt.SWTException;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.printing.Printer;
import org.eclipse.swt.printing.PrinterData;
import org.eclipse.swt.widgets.Display;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A print job on an SWT {@link Printer}: black on white, with {@link Palette#LIGHT}'s token colors
 * and each boxed char span drawn as a {@link ControlBoxes} box. The Printer is created and disposed
 * on the UI thread: on GTK its constructor runs a main loop in {@code gtk_enumerate_printers}, and
 * on macOS it creates an {@code NSWindow}. Everything between runs on one platform worker. SWT
 * checks the thread on widgets only, not on a Printer or its GC.
 *
 * <p>Margins are measured from the paper's edge through {@code computeTrim}. A device depth of 1
 * prints black; GTK reports 0 and macOS the screen's depth, so there the print system converts
 * color for a monochrome printer. Colors are created without a device and hold no system resource.
 */
final class PrintJob implements PrintTarget {
  private static final Logger LOG = LoggerFactory.getLogger(PrintJob.class);
  private static final Color BLACK = new Color(0, 0, 0);

  /** Size of the text and the header. */
  private static final int POINTS = 10;

  /** Paper margin, in hundredths of an inch. */
  private static final int MARGIN = 75;

  private static final String MEASURE = "0".repeat(100);

  /** A control box's outline, in hundredths of a point. */
  private static final int RULE = 75;

  private static final String HEX_DIGITS = "0123456789ABCDEF";

  private final Printer printer;
  private final PrinterData data;
  private final String family;
  private final String bodyFamily;
  private final SwtUiDispatcher ui;
  private final Display display;
  private final boolean color;
  private final Font[] fonts = new Font[4];
  private GC gc;
  private MarkdownPrinting rendered;
  private boolean jobOpen;
  private String name = "";
  private double advance;
  private int rowHeight;
  private int left;
  private int right;
  private int top;
  private int bottom;
  private int body;
  private int boxRule;
  private Font labelFont;

  /**
   * Creates the job; call on the UI thread.
   *
   * @param printer the printer, which the job disposes
   * @param data what the dialog chose
   * @param family the font family of source text and of rendered Markdown's code
   * @param display the Display, on whose thread the Printer is disposed; its system font's family
   *     prints rendered Markdown's text
   */
  PrintJob(Printer printer, PrinterData data, String family, Display display) {
    this.printer = printer;
    this.data = data;
    this.family = family;
    this.bodyFamily = display.getSystemFont().getFontData()[0].getName();
    this.display = display;
    this.ui = new SwtUiDispatcher(display);
    this.color = printer.getDepth() != 1;
  }

  @Override
  public boolean selectionOnly() {
    return data.scope == PrinterData.SELECTION;
  }

  @Override
  public int firstPage() {
    return data.scope == PrinterData.PAGE_RANGE ? Math.max(1, data.startPage) : 1;
  }

  @Override
  public int lastPage() {
    return data.scope == PrinterData.PAGE_RANGE ? data.endPage : Integer.MAX_VALUE;
  }

  @Override
  public Frame start(String document) {
    try {
      open(document);
      return new Frame((int) ((right - left) / advance), (bottom - body) / rowHeight);
    } catch (SWTError | SWTException e) {
      throw new PrintFailure("The print job did not start", e);
    }
  }

  @Override
  public MarkdownPrintSheet startMarkdown(String document, MarkdownPreviewContent content) {
    try {
      open(document);
      rendered =
          new MarkdownPrinting(
              printer,
              gc,
              new Rectangle(left, body, right - left, bottom - body),
              content,
              bodyFamily,
              family,
              color);
      return new RenderedSheet(rendered);
    } catch (SWTError | SWTException e) {
      throw new PrintFailure("The print job did not start", e);
    }
  }

  /** Starts the job and measures the margins, the header and the source text's columns. */
  private void open(String document) {
    name = document;
    jobOpen = printer.startJob(document);
    if (!jobOpen) {
      throw new PrintFailure("The print job did not start", null);
    }
    gc = new GC(printer);
    gc.setFont(font(false, false));
    advance = gc.stringExtent(MEASURE).x / (double) MEASURE.length();
    rowHeight = gc.getFontMetrics().getHeight();
    Rectangle client = printer.getClientArea();
    Rectangle trim = printer.computeTrim(0, 0, 0, 0);
    Point dpi = printer.getDPI();
    int marginX = dpi.x * MARGIN / 100;
    int marginY = dpi.y * MARGIN / 100;
    left = Math.max(client.x, trim.x + marginX);
    right = Math.min(client.x + client.width, trim.x + trim.width + client.width - marginX);
    top = Math.max(client.y, trim.y + marginY);
    bottom = Math.min(client.y + client.height, trim.y + trim.height + client.height - marginY);
    body = top + rowHeight * 2;
    boxRule = Math.max(1, Math.round(dpi.x * RULE / 7200f));
    labelFont = labelFont();
  }

  /** The rendered Markdown pages of a job, drawn by {@link MarkdownPrinting}. */
  private final class RenderedSheet implements MarkdownPrintSheet {
    private final MarkdownPrinting pages;

    RenderedSheet(MarkdownPrinting pages) {
      this.pages = pages;
    }

    @Override
    public Body body() {
      return new Body(right - left, bottom - body, pages.padding());
    }

    @Override
    public int width(int start, int end, boolean strong) {
      try {
        return pages.width(start, end, strong);
      } catch (SWTError | SWTException e) {
        throw new PrintFailure("Measuring failed", e);
      }
    }

    @Override
    public int indent(int line) {
      return pages.indent(line);
    }

    @Override
    public void image(MarkdownImageRequest request, byte[] bytes) {
      try {
        pages.image(request, bytes);
      } catch (SWTError | SWTException e) {
        throw new PrintFailure("An image failed", e);
      }
    }

    @Override
    public int[] rows(int line, MarkdownTableLayout.Row table) {
      try {
        return pages.rows(line, table);
      } catch (SWTError | SWTException e) {
        throw new PrintFailure("Measuring failed", e);
      }
    }

    @Override
    public void page(MarkdownPrintSheet.Page page) {
      try {
        if (!printer.startPage()) {
          throw new PrintFailure("A page did not start", null);
        }
        header(page.number(), page.total());
        pages.draw(page.lines());
        printer.endPage();
      } catch (SWTError | SWTException e) {
        throw new PrintFailure("A page failed", e);
      }
    }
  }

  @Override
  public void page(Page page) {
    try {
      if (!printer.startPage()) {
        throw new PrintFailure("A page did not start", null);
      }
      header(page.number(), page.total());
      int y = body;
      for (Row row : page.rows()) {
        if (row.line() > 0) {
          String number = Integer.toString(row.line());
          gc.setFont(font(false, false));
          draw(number, page.gutter() - 1 - number.length(), y, Palette.LIGHT.lineNumbers());
        }
        for (Span span : row.spans()) {
          TokenStyle style = span.role() == null ? null : Palette.LIGHT.style(span.role());
          if (span.text().length() == 1 && ControlChars.boxed(span.text().charAt(0))) {
            box(span, page.gutter() + span.column(), y, style == null ? null : style.foreground());
            continue;
          }
          gc.setFont(font(style != null && style.bold(), style != null && style.italic()));
          draw(
              span.text(),
              page.gutter() + span.column(),
              y,
              style == null ? null : style.foreground());
        }
        y += rowHeight;
      }
      printer.endPage();
    } catch (SWTError | SWTException e) {
      throw new PrintFailure("A page failed", e);
    }
  }

  private void header(int number, int total) {
    gc.setFont(font(false, false));
    gc.setForeground(BLACK);
    String label = UiText.PRINT_PAGE.format(number, total);
    int columns = (int) ((right - left) / advance) - label.length() - 1;
    String title = name;
    if (title.length() > columns) {
      title = "..." + title.substring(title.length() - Math.max(0, columns - 3));
    }
    gc.drawString(title, left, top, true);
    gc.drawString(label, right - gc.stringExtent(label).x, top, true);
    int rule = top + rowHeight + rowHeight / 2;
    gc.drawLine(left, rule, right, rule);
  }

  private void draw(String text, int column, int y, Rgb rgb) {
    gc.setForeground(color && rgb != null ? new Color(rgb.red(), rgb.green(), rgb.blue()) : BLACK);
    gc.drawString(text, left + (int) Math.round(column * advance), y, true);
  }

  /**
   * Draws a boxed char over two columns, in {@link ControlBoxes#ink} on white, with a 0.75 pt
   * outline and the label in {@link #labelFont}.
   */
  private void box(Span span, int column, int y, Rgb rgb) {
    int x = left + (int) Math.round(column * advance);
    int width = left + (int) Math.round((column + 2) * advance) - x;
    Color ink = BLACK;
    if (color) {
      Rgb on = ControlBoxes.ink(rgb == null ? Rgb.of(0) : rgb, Rgb.of(0xffffff));
      ink = new Color(on.red(), on.green(), on.blue());
    }
    ControlBoxes.drawRuled(
        gc,
        labelFont,
        ink,
        x,
        y,
        width,
        rowHeight,
        boxRule,
        ControlChars.label(span.text().charAt(0)));
  }

  /** Creates the box label font: the largest height whose widest label fits a box's label area. */
  private Font labelFont() {
    int cell = (int) Math.ceil(2 * advance - 1);
    Rectangle area = ControlBoxes.labelArea(0, 0, cell, rowHeight, boxRule);
    int points =
        ControlBoxes.labelHeight(
            POINTS,
            new Point(area.width, area.height),
            new IntFunction<Point>() {
              @Override
              public Point apply(int height) {
                Font trial = new Font(printer, family, height, SWT.NORMAL);
                try {
                  gc.setFont(trial);
                  return widestLabel();
                } finally {
                  gc.setFont(font(false, false));
                  trial.dispose();
                }
              }
            });
    return new Font(printer, family, points, SWT.NORMAL);
  }

  /** Returns two of the GC font's widest hex digit, as wide and as high as its text extent. */
  private Point widestLabel() {
    int width = 0;
    int height = 0;
    for (int i = 0; i < HEX_DIGITS.length(); i++) {
      Point digit = gc.stringExtent(HEX_DIGITS.substring(i, i + 1));
      width = Math.max(width, digit.x);
      height = Math.max(height, digit.y);
    }
    return new Point(2 * width, height);
  }

  private Font font(boolean bold, boolean italic) {
    int index = (bold ? 1 : 0) + (italic ? 2 : 0);
    if (fonts[index] == null) {
      int style = (bold ? SWT.BOLD : SWT.NORMAL) | (italic ? SWT.ITALIC : SWT.NORMAL);
      fonts[index] = new Font(printer, family, POINTS, style);
    }
    return fonts[index];
  }

  @Override
  public void finish() {
    try {
      releaseDrawing();
      jobOpen = false;
      printer.endJob();
    } catch (SWTError | SWTException e) {
      throw new PrintFailure("The print job did not end", e);
    }
  }

  @Override
  public void close() {
    try {
      releaseDrawing();
      if (jobOpen) {
        jobOpen = false;
        printer.cancelJob();
      }
    } catch (SWTError | SWTException e) {
      LOG.warn("Print job cleanup failed", e);
    } finally {
      disposePrinter();
    }
  }

  private void disposePrinter() {
    if (display.isDisposed()) {
      printer.dispose();
      return;
    }
    ui.asyncExec(
        new Runnable() {
          @Override
          public void run() {
            printer.dispose();
          }
        });
  }

  private void releaseDrawing() {
    if (rendered != null) {
      rendered.dispose();
      rendered = null;
    }
    if (gc != null) {
      gc.dispose();
      gc = null;
    }
    if (labelFont != null) {
      labelFont.dispose();
      labelFont = null;
    }
    for (int i = 0; i < fonts.length; i++) {
      if (fonts[i] != null) {
        fonts[i].dispose();
        fonts[i] = null;
      }
    }
  }
}
