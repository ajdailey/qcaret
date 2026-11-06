package io.github.ajdailey.qcaret.ui.swt;

import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.theme.Palette;
import io.github.ajdailey.qcaret.app.theme.Rgb;
import io.github.ajdailey.qcaret.app.theme.TokenStyle;
import io.github.ajdailey.qcaret.app.view.PrintTarget;
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
 * A print job on an SWT {@link Printer}: black on white, with {@link Palette#LIGHT}'s token colors.
 * The Printer is created and disposed on the UI thread: on GTK its constructor runs a main loop in
 * {@code gtk_enumerate_printers}, and on macOS it creates an {@code NSWindow}. Everything between
 * runs on one platform worker. SWT checks the thread on widgets only, not on a Printer or its GC.
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

  private final Printer printer;
  private final PrinterData data;
  private final String family;
  private final SwtUiDispatcher ui;
  private final Display display;
  private final boolean color;
  private final Font[] fonts = new Font[4];
  private GC gc;
  private boolean jobOpen;
  private String name = "";
  private double advance;
  private int rowHeight;
  private int left;
  private int right;
  private int top;
  private int body;

  /**
   * Creates the job; call on the UI thread.
   *
   * @param printer the printer, which the job disposes
   * @param data what the dialog chose
   * @param family the font family
   * @param display the Display, on whose thread the Printer is disposed
   */
  PrintJob(Printer printer, PrinterData data, String family, Display display) {
    this.printer = printer;
    this.data = data;
    this.family = family;
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
    name = document;
    try {
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
      int bottom =
          Math.min(client.y + client.height, trim.y + trim.height + client.height - marginY);
      body = top + rowHeight * 2;
      return new Frame((int) ((right - left) / advance), (bottom - body) / rowHeight);
    } catch (SWTError | SWTException e) {
      throw new PrintFailure("The print job did not start", e);
    }
  }

  @Override
  public void page(Page page) {
    try {
      if (!printer.startPage()) {
        throw new PrintFailure("A page did not start", null);
      }
      header(page);
      int y = body;
      for (Row row : page.rows()) {
        if (row.line() > 0) {
          String number = Integer.toString(row.line());
          gc.setFont(font(false, false));
          draw(number, page.gutter() - 1 - number.length(), y, Palette.LIGHT.lineNumbers());
        }
        for (Span span : row.spans()) {
          TokenStyle style = span.role() == null ? null : Palette.LIGHT.style(span.role());
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

  private void header(Page page) {
    gc.setFont(font(false, false));
    gc.setForeground(BLACK);
    String label = UiText.PRINT_PAGE.format(page.number(), page.total());
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
    if (gc != null) {
      gc.dispose();
      gc = null;
    }
    for (int i = 0; i < fonts.length; i++) {
      if (fonts[i] != null) {
        fonts[i].dispose();
        fonts[i] = null;
      }
    }
  }
}
