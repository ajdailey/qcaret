package io.github.ajdailey.qcaret.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.ajdailey.qcaret.app.theme.Rgb;
import io.github.ajdailey.qcaret.app.view.PrintTarget;
import java.awt.geom.AffineTransform;
import java.awt.geom.GeneralPath;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDVectorFont;
import org.apache.pdfbox.pdmodel.graphics.color.PDColor;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.util.Matrix;
import org.apache.pdfbox.util.Vector;
import org.eclipse.swt.SWT;
import org.eclipse.swt.printing.Printer;
import org.eclipse.swt.printing.PrinterData;
import org.eclipse.swt.widgets.Display;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A boxed char printed by {@link PrintJob} to a PDF file, read back from the page's content stream:
 * every path painted around the label is a rule 0.25 to 1.5 pt thick, nothing painted covers the
 * label's middle, the label's ink sits inside the outline with a rule of white on every side, and
 * the label's color contrasts at least 3:1 with white. Prints to GTK's Print to File printer, a
 * Windows printer named PDF, or on cocoa a save job with no printer; skips where none writes a
 * file. On failure the PDF and the painting operations near the label go to {@code
 * target/diagnostics/print-box/}.
 */
@Tag("swt")
class PrintBoxTest {
  private static final Duration BOUND = Duration.ofSeconds(60);
  private static final String FILE_BACKEND = "GtkPrintBackendFile";
  private static final double MIN_RULE = 0.25;
  private static final double MAX_RULE = 1.5;

  @TempDir Path temp;

  private Display display;
  private Path pdf;

  @BeforeEach
  void open() {
    display = new Display();
    pdf = temp.resolve("box.pdf");
  }

  @AfterEach
  void close() {
    if (display != null) {
      display.dispose();
    }
  }

  /** Returns a printer that writes {@link #pdf}, or null. */
  private PrinterData filePrinter() {
    if ("cocoa".equals(SWT.getPlatform())) {
      PrinterData data = new PrinterData("cocoa", "mock-pdf");
      data.printToFile = true;
      data.fileName = pdf.toString();
      return data;
    }
    for (PrinterData data : Printer.getPrinterList()) {
      boolean windowsPdf =
          "win32".equals(SWT.getPlatform()) && data.name.toLowerCase(Locale.ROOT).contains("pdf");
      if (FILE_BACKEND.equals(data.driver) || windowsPdf) {
        data.printToFile = true;
        data.fileName = pdf.toString();
        return data;
      }
    }
    return null;
  }

  @Test
  void aBoxPrintsAsAThinRuleAroundALabelOnWhite() throws Exception {
    PrinterData data = filePrinter();
    assumeTrue(data != null, "no printer writes a file here");
    print(data);
    Page page = read();
    String report = page.report();
    try {
      assertEquals(2, page.label.size(), report);
      for (Glyph glyph : page.label) {
        assertTrue(
            contrast(glyph.rgb, 0xffffff) >= 3.0,
            String.format("label %06x on white%n%s", glyph.rgb, report));
      }
      Point2D middle = page.middle();
      List<Paint> near = page.near();
      assertTrue(!near.isEmpty(), "no outline\n" + report);
      for (Paint paint : near) {
        assertTrue(!paint.covers(middle), "covers the label: " + paint + "\n" + report);
        double rule = paint.rule();
        assertTrue(rule >= MIN_RULE && rule <= MAX_RULE, "rule " + rule + " pt\n" + report);
      }
      String margins = page.margins(near);
      assertTrue(margins.startsWith("inside"), margins + "\n" + report);
    } catch (AssertionError e) {
      keep(report);
      throw e;
    }
  }

  private void print(PrinterData data) {
    Printer printer = new Printer(data);
    String family = display.getSystemFont().getFontData()[0].getName();
    PrintJob job = new PrintJob(printer, data, family, display);
    try {
      job.start("box");
      List<PrintTarget.Row> rows = new ArrayList<PrintTarget.Row>();
      rows.add(new PrintTarget.Row(0, List.<PrintTarget.Span>of()));
      rows.add(new PrintTarget.Row(0, List.<PrintTarget.Span>of()));
      rows.add(new PrintTarget.Row(0, List.of(new PrintTarget.Span("\u001b", 4, null))));
      job.page(new PrintTarget.Page(1, 1, 0, rows));
      job.finish();
    } finally {
      job.close();
    }
    assertTrue(
        EventLoop.runUntil(
            display,
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return printer.isDisposed() && pages() > 0;
              }
            },
            BOUND),
        "the PDF");
  }

  private int pages() {
    try (PDDocument document = Loader.loadPDF(pdf.toFile())) {
      return document.getNumberOfPages();
    } catch (IOException e) {
      return -1;
    }
  }

  private Page read() throws IOException {
    try (PDDocument document = Loader.loadPDF(pdf.toFile())) {
      Page page = new Page(document.getPage(0));
      page.processPage(document.getPage(0));
      return page;
    }
  }

  /** Copies the PDF and the report to {@code target/diagnostics/print-box/}. */
  private void keep(String report) throws IOException {
    Path dir = Files.createDirectories(Path.of("target", "diagnostics", "print-box"));
    Files.copy(pdf, dir.resolve("box.pdf"), StandardCopyOption.REPLACE_EXISTING);
    Files.writeString(dir.resolve("report.txt"), report);
  }

  static double contrast(int a, int b) {
    Rgb one = Rgb.of(a);
    Rgb two = Rgb.of(b);
    double la = luminance(one);
    double lb = luminance(two);
    return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
  }

  private static double luminance(Rgb rgb) {
    return 0.2126 * linear(rgb.red()) + 0.7152 * linear(rgb.green()) + 0.0722 * linear(rgb.blue());
  }

  private static double linear(int component) {
    double c = component / 255.0;
    return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
  }

  private static int rgb(PDColor color) {
    try {
      return color.toRGB();
    } catch (IOException | UnsupportedOperationException e) {
      return -1;
    }
  }

  /**
   * A shown glyph in page space.
   *
   * @param ink the bounds of the glyph's outline, or null for a glyph with none
   */
  private record Glyph(
      String text, double x, double y, double width, double size, Rectangle2D ink, int rgb) {}

  /**
   * A painted path or image in page space.
   *
   * @param kind {@code fill}, {@code stroke} or {@code image}
   * @param shape the path, or the image's bounds
   * @param width the stroke's width in points, 0 for a fill or an image
   * @param rgb the paint color, or -1 for an image
   */
  private record Paint(String kind, Path2D shape, double width, int rgb) {
    boolean covers(Point2D point) {
      if (!"stroke".equals(kind)) {
        return rgb != 0xffffff && shape.contains(point);
      }
      return distance(shape, point) < width / 2;
    }

    /** Returns the thickness this paint lays down: the stroke's width, or a fill's least side. */
    double rule() {
      if ("stroke".equals(kind)) {
        return width;
      }
      Rectangle2D bounds = shape.getBounds2D();
      return Math.min(bounds.getWidth(), bounds.getHeight());
    }

    @Override
    public String toString() {
      Rectangle2D b = shape.getBounds2D();
      return String.format(
          Locale.ROOT,
          "%s %06x w=%.3f bounds=[%.2f %.2f %.2f %.2f]",
          kind,
          rgb,
          width,
          b.getX(),
          b.getY(),
          b.getWidth(),
          b.getHeight());
    }
  }

  private static double distance(Path2D shape, Point2D point) {
    double least = Double.MAX_VALUE;
    double[] c = new double[6];
    double startX = 0;
    double startY = 0;
    double lastX = 0;
    double lastY = 0;
    for (PathIterator it = shape.getPathIterator(null, 0.1); !it.isDone(); it.next()) {
      int type = it.currentSegment(c);
      if (type == PathIterator.SEG_MOVETO) {
        startX = c[0];
        startY = c[1];
        lastX = c[0];
        lastY = c[1];
      } else if (type == PathIterator.SEG_LINETO) {
        least =
            Math.min(least, Line2D.ptSegDist(lastX, lastY, c[0], c[1], point.getX(), point.getY()));
        lastX = c[0];
        lastY = c[1];
      } else if (type == PathIterator.SEG_CLOSE) {
        least =
            Math.min(
                least, Line2D.ptSegDist(lastX, lastY, startX, startY, point.getX(), point.getY()));
        lastX = startX;
        lastY = startY;
      }
    }
    return least;
  }

  /** The glyphs and painted paths of one page, from its content stream. */
  private static final class Page extends PDFGraphicsStreamEngine {
    private final List<Glyph> glyphs = new ArrayList<Glyph>();
    private final List<Glyph> label = new ArrayList<Glyph>();
    private final List<Paint> paints = new ArrayList<Paint>();
    private Path2D path = new Path2D.Double();

    Page(PDPage page) {
      super(page);
    }

    @Override
    protected void showGlyph(Matrix matrix, PDFont font, int code, Vector displacement)
        throws IOException {
      super.showGlyph(matrix, font, code, displacement);
      String text = font.toUnicode(code);
      Glyph glyph =
          new Glyph(
              text == null ? "?" : text,
              matrix.getTranslateX(),
              matrix.getTranslateY(),
              displacement.getX() * matrix.getScalingFactorX(),
              matrix.getScalingFactorY(),
              ink(matrix, font, code),
              rgb(getGraphicsState().getNonStrokingColor()));
      glyphs.add(glyph);
      int at = glyphs.size();
      if (at >= 2 && "1".equals(glyphs.get(at - 2).text) && "B".equals(text)) {
        label.clear();
        label.add(glyphs.get(at - 2));
        label.add(glyph);
      }
    }

    /**
     * Returns the bounds of a glyph's outline in page space, or null where the font has no outline
     * or the glyph is empty. Measured from the outline: a PDF writer's {@code CapHeight} can be the
     * font's bounding box top (cairo writes 1232 for DejaVu Sans).
     */
    private static Rectangle2D ink(Matrix matrix, PDFont font, int code) throws IOException {
      if (!(font instanceof PDVectorFont vector)) {
        return null;
      }
      GeneralPath outline = vector.getNormalizedPath(code);
      if (outline == null || outline.getBounds2D().isEmpty()) {
        return null;
      }
      AffineTransform at = matrix.createAffineTransform();
      at.concatenate(font.getFontMatrix().createAffineTransform());
      return at.createTransformedShape(outline).getBounds2D();
    }

    /**
     * Measures the space between the label's ink, the union of its glyph outlines, and the inner
     * edge of the outline the paints near it form.
     *
     * @param near the outline's paints
     * @return {@code inside} when each side has at least one rule of white, else {@code outside},
     *     then the four margins and the rule in points
     */
    String margins(List<Paint> near) {
      double rule = Double.MAX_VALUE;
      Rectangle2D outer = null;
      for (Paint paint : near) {
        rule = Math.min(rule, paint.rule());
        Rectangle2D bounds = paint.shape.getBounds2D();
        outer = outer == null ? bounds : outer.createUnion(bounds);
      }
      Glyph first = label.get(0);
      if (first.ink == null || label.get(1).ink == null) {
        return "outside: a label glyph has no outline";
      }
      Rectangle2D ink = first.ink.createUnion(label.get(1).ink);
      double left = ink.getMinX() - (outer.getMinX() + rule);
      double right = outer.getMaxX() - rule - ink.getMaxX();
      double bottom = ink.getMinY() - (outer.getMinY() + rule);
      double top = outer.getMaxY() - rule - ink.getMaxY();
      double least = Math.min(Math.min(left, right), Math.min(top, bottom));
      return String.format(
          Locale.ROOT,
          "%s: left %.2f top %.2f right %.2f bottom %.2f, rule %.2f, label %.2f pt",
          least >= rule - 0.01 ? "inside" : "outside",
          left,
          top,
          right,
          bottom,
          rule,
          first.size);
    }

    /** Returns the label's middle: half way across, a third of the size above the baseline. */
    Point2D middle() {
      Glyph first = label.get(0);
      Glyph last = label.get(1);
      return new Point2D.Double((first.x + last.x + last.width) / 2, first.y + first.size / 3);
    }

    /** Returns the paints other than white whose bounds meet the label's cell and its margin. */
    List<Paint> near() {
      List<Paint> out = new ArrayList<Paint>();
      if (label.size() != 2) {
        return out;
      }
      Glyph first = label.get(0);
      Glyph last = label.get(1);
      double size = first.size;
      Rectangle2D cell =
          new Rectangle2D.Double(
              first.x - size / 2,
              first.y - size / 2,
              last.x + last.width - first.x + size,
              size * 1.5);
      for (Paint paint : paints) {
        if (paint.rgb != 0xffffff && paint.shape.getBounds2D().intersects(cell)) {
          out.add(paint);
        }
      }
      return out;
    }

    String report() {
      StringBuilder out = new StringBuilder();
      for (Glyph glyph : glyphs) {
        out.append(
            String.format(
                Locale.ROOT,
                "glyph %s %06x at %.2f %.2f w=%.2f size=%.2f%n",
                glyph.text,
                glyph.rgb,
                glyph.x,
                glyph.y,
                glyph.width,
                glyph.size));
      }
      for (Paint paint : paints) {
        out.append(paint).append('\n');
      }
      return out.toString();
    }

    /**
     * Adds one paint per subpath: Microsoft Print to PDF fills the four bands of a box as two paths
     * of two rectangles each.
     */
    private void paint(String kind, double width, int rgb) {
      Path2D part = null;
      double[] c = new double[6];
      for (PathIterator it = path.getPathIterator(null); !it.isDone(); it.next()) {
        int type = it.currentSegment(c);
        if (type == PathIterator.SEG_MOVETO) {
          if (part != null) {
            paints.add(new Paint(kind, part, width, rgb));
          }
          part = new Path2D.Double(path.getWindingRule());
          part.moveTo(c[0], c[1]);
        } else if (part == null) {
          continue;
        } else if (type == PathIterator.SEG_LINETO) {
          part.lineTo(c[0], c[1]);
        } else if (type == PathIterator.SEG_QUADTO) {
          part.quadTo(c[0], c[1], c[2], c[3]);
        } else if (type == PathIterator.SEG_CUBICTO) {
          part.curveTo(c[0], c[1], c[2], c[3], c[4], c[5]);
        } else {
          part.closePath();
        }
      }
      if (part != null) {
        paints.add(new Paint(kind, part, width, rgb));
      }
      path = new Path2D.Double();
    }

    @Override
    public void appendRectangle(Point2D p0, Point2D p1, Point2D p2, Point2D p3) {
      path.moveTo(p0.getX(), p0.getY());
      path.lineTo(p1.getX(), p1.getY());
      path.lineTo(p2.getX(), p2.getY());
      path.lineTo(p3.getX(), p3.getY());
      path.closePath();
    }

    @Override
    public void drawImage(PDImage image) {
      Matrix ctm = getGraphicsState().getCurrentTransformationMatrix();
      AffineTransform at = ctm.createAffineTransform();
      Path2D bounds = new Path2D.Double(new Rectangle2D.Double(0, 0, 1, 1), at);
      paints.add(new Paint("image", bounds, 0, -1));
    }

    @Override
    public void clip(int windingRule) {}

    @Override
    public void moveTo(float x, float y) {
      path.moveTo(x, y);
    }

    @Override
    public void lineTo(float x, float y) {
      path.lineTo(x, y);
    }

    @Override
    public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) {
      path.curveTo(x1, y1, x2, y2, x3, y3);
    }

    @Override
    public Point2D getCurrentPoint() {
      Point2D point = path.getCurrentPoint();
      return point == null ? new Point2D.Float() : point;
    }

    @Override
    public void closePath() {
      path.closePath();
    }

    @Override
    public void endPath() {
      path = new Path2D.Double();
    }

    @Override
    public void strokePath() {
      Matrix ctm = getGraphicsState().getCurrentTransformationMatrix();
      double width = getGraphicsState().getLineWidth() * ctm.getScalingFactorX();
      paint("stroke", width, rgb(getGraphicsState().getStrokingColor()));
    }

    @Override
    public void fillPath(int windingRule) {
      path.setWindingRule(windingRule);
      paint("fill", 0, rgb(getGraphicsState().getNonStrokingColor()));
    }

    @Override
    public void fillAndStrokePath(int windingRule) {
      Path2D kept = (Path2D) path.clone();
      fillPath(windingRule);
      path = kept;
      strokePath();
    }

    @Override
    public void shadingFill(COSName name) {}
  }
}
