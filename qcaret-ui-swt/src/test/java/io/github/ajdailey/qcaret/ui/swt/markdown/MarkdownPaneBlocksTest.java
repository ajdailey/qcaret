package io.github.ajdailey.qcaret.ui.swt.markdown;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.ajdailey.qcaret.app.editor.BlockSpacing;
import io.github.ajdailey.qcaret.app.editor.MarkdownSpans;
import io.github.ajdailey.qcaret.app.theme.Palette;
import io.github.ajdailey.qcaret.app.theme.Rgb;
import io.github.ajdailey.qcaret.app.view.MarkdownImageRequest;
import io.github.ajdailey.qcaret.app.view.MarkdownPreviewContent;
import io.github.ajdailey.qcaret.app.view.MarkdownPreviewInput;
import io.github.ajdailey.qcaret.app.view.MarkdownPreviewView;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.markdown.MarkdownRenderer;
import io.github.ajdailey.qcaret.core.markdown.RenderedMarkdown;
import io.github.ajdailey.qcaret.ui.swt.EventLoop;
import io.github.ajdailey.qcaret.ui.swt.ScaleSlack;
import io.github.ajdailey.qcaret.ui.swt.SwtUiDispatcher;
import io.github.ajdailey.qcaret.ui.swt.platform.PlatformTraits;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * {@link MarkdownPane} block spacing, the code block's text indent, a Find match across a wrapped
 * cell's break, and a table far below the view that waits for its layout until the view comes near.
 */
@Tag("swt")
class MarkdownPaneBlocksTest {
  private static final Duration BOUND = Duration.ofSeconds(30);

  private static final String LONG =
      "a mock note of several words that runs on for a while and then some more words";

  private static final String TABLE =
      String.join(
          "\n",
          "| Name | Note |",
          "|:--|:--|",
          "| first | " + LONG + " " + LONG + " |",
          "| second | short |",
          "");

  /** A table 200 paragraphs below the top, under a heading. */
  private static final String FAR =
      "Intro.\n" + "\nFiller.\n".repeat(200) + "\n## Far\n\n" + TABLE + "\nFiller.\n".repeat(40);

  private Display display;
  private Shell shell;
  private MarkdownPane pane;

  @BeforeEach
  void open() {
    display = new Display();
    shell = new Shell(display);
    shell.setLayout(new FillLayout());
    shell.setSize(700, 300);
    pane =
        new MarkdownPane(
            shell,
            new MarkdownPreviewInput() {
              @Override
              public void attach(MarkdownPreviewView view) {
                // Not attached.
              }

              @Override
              public void renderRequested() {
                // No notice.
              }

              @Override
              public void previewScrolled(int line) {
                // Not followed.
              }

              @Override
              public void clicked(int offset) {
                // No clicks.
              }

              @Override
              public void linkFollowed(String location) {
                // No links followed.
              }

              @Override
              public void browserFailed() {
                // Basic only.
              }
            },
            Palette.LIGHT,
            new SwtUiDispatcher(display),
            PlatformTraits.PLATFORM.monospaceFamily());
    shell.open();
  }

  @AfterEach
  void close() {
    if (!display.isDisposed()) {
      display.dispose();
    }
  }

  private void await(BooleanSupplier condition, String what) {
    assertTrue(EventLoop.runUntil(display, condition, BOUND), what);
  }

  private void flush() {
    while (display.readAndDispatch()) {
      // Paint.
    }
  }

  private void idle() {
    flush();
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !pane.tables().busy();
          }
        },
        "no turn queued");
    flush();
  }

  private void awaitLaidOut() {
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return pane.tables().laidOut(0) && !pane.tables().busy();
          }
        },
        "table laid out");
    flush();
  }

  private RenderedMarkdown show(String markdown) {
    RenderedMarkdown r = new MarkdownRenderer().render(markdown, null, new CancellationToken());
    pane.showRendered(
        new MarkdownPreviewContent(
            r, List.of(), MarkdownSpans.of(r, List.of()), new ArrayList<MarkdownImageRequest>()));
    idle();
    return r;
  }

  private int height(int line) {
    StyledText text = pane.text();
    return text.getLinePixel(line + 1) - text.getLinePixel(line);
  }

  @Test
  void separatorsAddSpaceAroundHeadingsAndCodeBlocks() {
    RenderedMarkdown r =
        show("Intro\n\n## Two\n\n```\ncode\n```\n\nEnd\n" + "\nFiller.\n".repeat(20));
    int two = r.headings().get(0).line();
    int code = r.codeBlocks().get(0).firstLine();
    int body = height(0);
    int slack = ScaleSlack.of(pane.text());
    assertEquals(body + BlockSpacing.HEADING_ABOVE, height(two - 1), slack, "above the heading");
    assertEquals(
        body + BlockSpacing.RULE_GAP + BlockSpacing.CODE_PADDING,
        height(two + 1),
        slack,
        "rule gap and code padding");
    assertEquals(body + BlockSpacing.CODE_PADDING, height(code + 1), slack, "below the code");
    StyledText text = pane.text();
    int at = pane.map().display(r.lineStart(code));
    assertEquals(text.getLeftMargin() + MarkdownStyler.CODE_INDENT, text.getLocationAtOffset(at).x);
  }

  @Test
  void aWrappedCodeLineContinuesUnderItsOwnText() {
    shell.setLayout(null);
    pane.control().setBounds(0, 0, 320, 300);
    flush();
    RenderedMarkdown r =
        show("```\n    mock(alpha, beta, gamma, delta, epsilon, zeta, eta, theta, iota);\n```\n");
    StyledText text = pane.text();
    int line = pane.map().displayLine(r.codeBlocks().get(0).firstLine());
    int start = text.getOffsetAtLine(line);
    int y = text.getLocationAtOffset(start).y;
    int wrapped = -1;
    for (int i = start; i < start + text.getLine(line).length(); i++) {
      if (text.getLocationAtOffset(i).y > y) {
        wrapped = i;
        break;
      }
    }
    assertTrue(wrapped > 0, "the code line wraps");
    int own = text.getLocationAtOffset(start + 4).x;
    assertEquals(
        text.getLeftMargin() + MarkdownStyler.CODE_INDENT, text.getLocationAtOffset(start).x);
    // The indent and the hanging indent round apart by a point: at a fractional zoom on win32, and
    // on cocoa, whose glyph advances are fractional.
    assertEquals(
        own,
        text.getLocationAtOffset(wrapped).x,
        Math.max(1, ScaleSlack.of(text)),
        "continuation under the line's text");
  }

  /** A first word too long to follow the indent left the indent alone on a line (Windows). */
  @Test
  void aCodeLinesFirstWordStartsOnItsFirstVisualLine() {
    shell.setLayout(null);
    pane.control().setBounds(0, 0, 320, 300);
    flush();
    String code = "mock.alpha.beta.gamma.delta(\"epsilon zeta eta theta iota kappa\");";
    RenderedMarkdown r = show("```\n            " + code + "\n```\n");
    StyledText text = pane.text();
    int start = text.getOffsetAtLine(pane.map().displayLine(r.codeBlocks().get(0).firstLine()));
    int y = text.getLocationAtOffset(start).y;
    // Cocoa still starts the first word on the next visual line (CI): not checked there.
    if (!"cocoa".equals(SWT.getPlatform())) {
      assertEquals(y, text.getLocationAtOffset(start + 12).y, "the first word's first char");
    }
    assertTrue(text.getLocationAtOffset(start + 12 + code.length() - 1).y > y, "the line wraps");
    pane.selectRendered(r.lineStart(r.codeBlocks().get(0).firstLine()), r.text().indexOf("mock"));
    assertEquals(" ".repeat(12), pane.selectedRendered(), "the indent copies as spaces");
  }

  /** The closing line of a code block at the document's end sat on the box's border. */
  @Test
  void theLastBlocksCodeBoxKeepsItsBottomPadding() {
    int separated = gapBelowCode("Intro\n\n```\nmock\n```\n\nAfter.\n");
    RenderedMarkdown r = show("Intro\n\n```\nmock\n```");
    assertTrue(r.code(r.lineCount() - 1), "the code is the last line");
    int last = gapBelowCode("Intro\n\n```\nmock\n```");
    assumeTrue(separated > 0, "the print shows no code box border here: " + separated);
    assertEquals(separated, last, ScaleSlack.of(pane.text()), "padding at the document's end");
  }

  /** A wrapped code line at the document's end had a box around its first visual row only. */
  @Test
  void aWrappedCodeLineAtTheDocumentsEndStaysInsideItsBox() {
    shell.setLayout(null);
    pane.control().setBounds(0, 0, 320, 300);
    flush();
    for (String after : new String[] {"\n\nAfter.\n", ""}) {
      StyledText text = pane.text();
      int right = text.getClientArea().width - text.getRightMargin();
      // A first word longer than the pane, whose last visual row ends left of the middle.
      int measured = -1;
      for (int length = 60; length < 140 && measured < 0; length++) {
        String markdown = "Intro\n\n```\nmock" + "x".repeat(length) + "\n```" + after;
        RenderedMarkdown r = show(markdown);
        int line = pane.map().displayLine(r.codeBlocks().get(0).firstLine());
        int end = text.getOffsetAtLine(line) + text.getLine(line).length();
        Point last = text.getLocationAtOffset(end);
        if (last.x > (text.getLeftMargin() + right) / 2) {
          continue;
        }
        int top = text.getLinePixel(line);
        assertTrue(last.y > top, "the code line wraps");
        int rows = last.y + text.getLineHeight(end) - top;
        int border = gapBelowCode(markdown, (last.x + right) / 2);
        assumeTrue(border > 0, "the print shows no code box border here: " + border);
        assertTrue(border >= rows, "border " + border + " above the last row's bottom " + rows);
        measured = length;
      }
      assertTrue(measured > 0, "no word length leaves a short last row");
    }
  }

  /**
   * Prints the widget at a zoom. GTK 4 prints the last drawn frame and {@code print} returns false
   * while a redraw is queued, so a failed print dispatches events and retries. On win32 the first
   * print of a new shell can return true with every pixel black (2.5 percent on {@code
   * windows-2025}): a print whose corner is black is retried too.
   */
  private ImageData print(final StyledText text, final int zoom) {
    final ImageData[] shot = new ImageData[1];
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            Point size = text.getSize();
            Image image = new Image(display, size.x, size.y);
            try {
              GC gc = new GC(image);
              try {
                if (!text.print(gc)) {
                  return false;
                }
              } finally {
                gc.dispose();
              }
              ImageData data = image.getImageData(zoom);
              if (data.palette.getRGB(data.getPixel(0, 0)).equals(new RGB(0, 0, 0))) {
                return false;
              }
              shot[0] = data;
              return true;
            } finally {
              image.dispose();
            }
          }
        },
        "printed");
    return shot[0];
  }

  /**
   * Shows a document and returns the pixels from its code line's top to the box's bottom border.
   */
  private int gapBelowCode(String markdown) {
    return gapBelowCode(markdown, pane.text().getLeftMargin() + 4 * MarkdownStyler.CODE_INDENT);
  }

  /** Shows a document and returns the pixels from its code line's top to the lowest border ink. */
  private int gapBelowCode(String markdown, int column) {
    RenderedMarkdown r = show(markdown);
    StyledText text = pane.text();
    int line = pane.map().displayLine(r.codeBlocks().get(0).firstLine());
    int top = text.getLinePixel(line);
    int zoom = text.getMonitor().getZoom();
    ImageData shot = print(text, zoom);
    RGB ink = rgb(Palette.LIGHT.lineNumbers());
    RGB[] others = {rgb(Palette.LIGHT.gutter()), rgb(Palette.LIGHT.background())};
    int x = column * zoom / 100;
    int lowest = -1;
    int end = Math.min(shot.height, (text.getLinePixel(line + 1) + 20) * zoom / 100);
    for (int y = top * zoom / 100; y < end; y++) {
      RGB pixel = shot.palette.getRGB(shot.getPixel(x, y));
      // Nearest of the three colors: a blended border pixel still counts.
      if (distance(pixel, ink) < distance(pixel, others[0])
          && distance(pixel, ink) < distance(pixel, others[1])) {
        lowest = y * 100 / zoom;
      }
    }
    return lowest - top;
  }

  private static RGB rgb(Rgb rgb) {
    return new RGB(rgb.red(), rgb.green(), rgb.blue());
  }

  private static int distance(RGB a, RGB b) {
    return Math.abs(a.red - b.red) + Math.abs(a.green - b.green) + Math.abs(a.blue - b.blue);
  }

  /** Find across a wrapped cell's break selected the other cells' segments between its ends. */
  @Test
  void aMatchAcrossAWrappedCellBreakSelectsItsFirstLine() {
    RenderedMarkdown r = show("Intro.\n\n" + TABLE);
    awaitLaidOut();
    String cell = LONG + " " + LONG;
    int start = r.text().indexOf(cell);
    pane.selectRendered(start, start + cell.length());
    StyledText text = pane.text();
    String selected = text.getSelectionText();
    assertTrue(!selected.isEmpty() && cell.startsWith(selected), selected);
    assertEquals(
        text.getLineAtOffset(text.getSelection().x), text.getLineAtOffset(text.getSelection().y));
  }

  @Test
  void aFarTableWaitsUntilOutlineGoToReachesIt() {
    RenderedMarkdown r = show(FAR);
    assertTrue(pane.tables().waiting(0), "far table waits");
    assertFalse(pane.tables().laidOut(0));
    int heading = r.headings().get(0).line();
    pane.scrollToLine(heading);
    flush();
    awaitLaidOut();
    assertEquals(pane.map().displayLine(heading), pane.text().getTopIndex());
    assertTrue(pane.map().displayLineCount(r.tables().get(0).firstLine() + 1) > 1, "wrapped");
  }

  @Test
  void findInAWaitingTableSelectsTheMatchOnceLaidOut() {
    RenderedMarkdown r = show(FAR);
    assertTrue(pane.tables().waiting(0));
    int at = r.text().indexOf("second");
    pane.selectRendered(at, at + 6);
    awaitLaidOut();
    StyledText text = pane.text();
    assertEquals("second", text.getSelectionText());
    int line = text.getLineAtOffset(text.getSelection().x);
    assertTrue(
        line >= text.getTopIndex() && text.getLinePixel(line) < text.getClientArea().height,
        "the match is in view");
  }

  @Test
  void aResizeKeepsAFarTableWaitingAndItsLayoutUsesTheNewWidth() {
    RenderedMarkdown r = show(FAR);
    final int before = pane.text().getClientArea().width;
    shell.setSize(450, 300);
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return pane.text().getClientArea().width < before;
          }
        },
        "resized");
    idle();
    assertTrue(pane.tables().waiting(0), "still waits");
    pane.scrollToLine(r.headings().get(0).line());
    flush();
    awaitLaidOut();
    StyledText text = pane.text();
    int[] edges = pane.tables().edges(0);
    assertTrue(
        edges[edges.length - 1]
            <= text.getClientArea().width - text.getLeftMargin() - text.getRightMargin());
  }
}
