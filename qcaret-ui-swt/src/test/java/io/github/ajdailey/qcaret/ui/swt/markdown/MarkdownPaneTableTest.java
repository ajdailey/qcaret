package io.github.ajdailey.qcaret.ui.swt.markdown;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.editor.MarkdownDisplayMap;
import io.github.ajdailey.qcaret.app.editor.MarkdownSpans;
import io.github.ajdailey.qcaret.app.editor.TableWrap;
import io.github.ajdailey.qcaret.app.theme.Palette;
import io.github.ajdailey.qcaret.app.view.MarkdownImageRequest;
import io.github.ajdailey.qcaret.app.view.MarkdownPreviewContent;
import io.github.ajdailey.qcaret.app.view.MarkdownPreviewInput;
import io.github.ajdailey.qcaret.app.view.MarkdownPreviewView;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.markdown.MarkdownLink;
import io.github.ajdailey.qcaret.core.markdown.MarkdownRenderer;
import io.github.ajdailey.qcaret.core.markdown.MarkdownTable;
import io.github.ajdailey.qcaret.core.markdown.RenderedMarkdown;
import io.github.ajdailey.qcaret.ui.swt.EventLoop;
import io.github.ajdailey.qcaret.ui.swt.SwtUiDispatcher;
import io.github.ajdailey.qcaret.ui.swt.platform.PlatformTraits;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * {@link MarkdownPane} over a table whose cells wrap in their columns: the display rows, Copy, the
 * controller's offsets and lines below the table, a refit on resize, a link in a wrapped cell, and
 * the cell padding.
 */
@Tag("swt")
class MarkdownPaneTableTest {
  private static final Duration BOUND = Duration.ofSeconds(30);

  private static final String LONG =
      "a mock note of several words that runs on for a while and then some more words";

  /** A paragraph and a heading below a table whose last column wraps at 700 px. */
  private static final String DOCUMENT =
      String.join(
              "\n",
              "Intro",
              "",
              "| Name | Value | Note |",
              "|:--|--:|:-:|",
              "| first | 1 | " + LONG + " " + LONG + " |",
              "| second | 22 | short |",
              "| third | 333 | " + LONG + " [mock link](#below) " + LONG + " |",
              "",
              "Paragraph after the table.",
              "",
              "## Below",
              "",
              "Last words.",
              "")
          + "\nFiller.\n".repeat(60);

  private Display display;
  private Shell shell;
  private MarkdownPane pane;
  private final List<Integer> clicks = new ArrayList<Integer>();
  private final List<Integer> scrolled = new ArrayList<Integer>();

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
                scrolled.add(line);
              }

              @Override
              public void clicked(int offset) {
                clicks.add(offset);
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

  private void awaitLaidOut() {
    flush();
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
    awaitLaidOut();
    return r;
  }

  private MarkdownDisplayMap map() {
    return pane.map();
  }

  @Test
  void aLongCellWrapsInsideItsColumn() {
    RenderedMarkdown r = show(DOCUMENT);
    MarkdownTable table = r.tables().get(0);
    int row = table.firstLine() + 1;
    assertTrue(map().displayLineCount(row) > 1, map().text());
    assertEquals(1, map().displayLineCount(row + 1));
    StyledText text = pane.text();
    int[] edges = pane.tables().edges(0);
    int left = text.getLeftMargin() - text.getHorizontalPixel();
    assertTrue(
        left + edges[edges.length - 1] <= text.getClientArea().width - text.getRightMargin());
    int second = map().displayLine(row) + 1;
    int segment = text.getOffsetAtLine(second) + text.getLine(second).lastIndexOf('\t') + 1;
    int x = text.getLocationAtOffset(segment).x;
    assertTrue(x >= left + edges[2] && x < left + edges[3], "continuation at " + x);
    assertEquals(second, text.getLineAtOffset(segment), "one visual line per display line");
    Point end = text.getLocationAtOffset(text.getOffsetAtLine(second + 1) - 1);
    assertTrue(end.y < text.getLinePixel(second + 1), "the display line does not wrap");
  }

  @Test
  void copyAcrossAWrappedRowGivesTheRenderedText() {
    RenderedMarkdown r = show(DOCUMENT);
    MarkdownTable table = r.tables().get(0);
    int row = table.firstLine() + 1;
    StyledText text = pane.text();
    int first = map().displayLine(row);
    int last = first + map().displayLineCount(row) - 1;
    text.setSelection(
        text.getOffsetAtLine(first), text.getOffsetAtLine(last) + text.getLine(last).length());
    int start = r.lineStart(row);
    assertEquals(r.text().substring(start, r.lineStart(row + 1) - 1), pane.selectedRendered());
    text.setSelection(text.getOffsetAtLine(first), text.getOffsetAtLine(last + 2));
    assertEquals(
        r.text().substring(start, r.lineStart(row + 2)),
        pane.selectedRendered(),
        "two rows and their line breaks");
    pane.copy();
  }

  @Test
  void findOutlineAndSyncScrollLandBelowAWrappedTable() {
    RenderedMarkdown r = show(DOCUMENT);
    StyledText text = pane.text();
    int after = r.text().indexOf("Paragraph after");
    pane.selectRendered(after, after + 9);
    assertEquals("Paragraph", text.getSelectionText());
    int heading = r.headings().get(0).line();
    pane.scrollToLine(heading);
    flush();
    assertEquals(map().displayLine(heading), text.getTopIndex());
    assertTrue(text.getLine(text.getTopIndex()).startsWith("Below"));
    scrolled.clear();
    int middle = r.lineCount() / 2;
    text.setTopIndex(map().displayLine(middle));
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !scrolled.isEmpty();
          }
        },
        "scroll reported");
    assertEquals(List.of(middle), scrolled);
  }

  @Test
  void aNarrowerPaneWrapsMoreAndKeepsTheTopLine() {
    resize(450, true);
  }

  @Test
  void aWiderPaneWrapsLessAndKeepsTheTopLine() {
    resize(1_200, false);
  }

  /**
   * Resizes the shell once from 700 px with "Paragraph after" at the top. GTK 4 applies a resize in
   * a later event, and two resizes in a row can end at the first size.
   */
  private void resize(int width, final boolean narrower) {
    RenderedMarkdown r = show(DOCUMENT);
    int after = r.text().indexOf("Paragraph after");
    int line = r.lineCount() - 1;
    for (int i = 0; i < r.lineCount(); i++) {
      if (r.lineStart(i) == after) {
        line = i;
      }
    }
    StyledText text = pane.text();
    final int row = r.tables().get(0).firstLine() + 1;
    final int lines = map().displayLineCount(row);
    pane.scrollToLine(line);
    flush();
    shell.setSize(width, 300);
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            int now = map().displayLineCount(row);
            return (narrower ? now > lines : now < lines) && !pane.tables().busy();
          }
        },
        narrower ? "narrower wraps more" : "wider wraps less");
    flush();
    assertTrue(text.getLine(text.getTopIndex()).startsWith("Paragraph after"));
  }

  @Test
  void aLinkOnAWrappedCellsSecondLineIsClicked() {
    RenderedMarkdown r = show(DOCUMENT);
    MarkdownLink link = r.links().get(0);
    int row = r.tables().get(0).firstLine() + 2;
    StyledText text = pane.text();
    int offset = map().display(link.start() + 2);
    assertTrue(text.getLineAtOffset(offset) > map().displayLine(row), "on a continuation line");
    Point at = text.getLocationAtOffset(offset);
    Event up = new Event();
    up.button = 1;
    up.x = at.x + 1;
    up.y = at.y + text.getLineHeight(offset) / 2;
    text.notifyListeners(SWT.MouseUp, up);
    assertEquals(1, clicks.size());
    int clicked = clicks.get(0);
    assertTrue(link.start() <= clicked && clicked < link.end(), "clicked " + clicked);
  }

  /** Rows laid out again on each render flashed unwrapped rows while typing in Split. */
  @Test
  void aNewRenderKeepsTheRowsOfAnEqualTable() {
    RenderedMarkdown r = show(DOCUMENT);
    int row = r.tables().get(0).firstLine() + 1;
    int lines = map().displayLineCount(row);
    assertTrue(lines > 1);
    RenderedMarkdown next =
        new MarkdownRenderer()
            .render("New first line.\n\n" + DOCUMENT, null, new CancellationToken());
    pane.showRendered(
        new MarkdownPreviewContent(
            next,
            List.of(),
            MarkdownSpans.of(next, List.of()),
            new ArrayList<MarkdownImageRequest>()));
    assertTrue(!pane.tables().busy(), "no layout turn");
    int moved = next.tables().get(0).firstLine() + 1;
    assertEquals(lines, map().displayLineCount(moved));
    assertEquals(map().text(), pane.text().getText());
  }

  @Test
  void rowsArePaddedAboveAndBelow() {
    RenderedMarkdown r = show(DOCUMENT);
    StyledText text = pane.text();
    int intro = text.getLinePixel(1) - text.getLinePixel(0);
    int wrapped = map().displayLine(r.tables().get(0).firstLine() + 1);
    int last = wrapped + map().displayLineCount(r.tables().get(0).firstLine() + 1) - 1;
    int single = last + 1;
    int height = text.getLinePixel(single + 1) - text.getLinePixel(single);
    assertEquals(intro + TableColumns.PAD_ABOVE + TableColumns.PAD_BELOW, height, "one-line row");
    String pad = String.valueOf(TableWrap.PAD);
    assertTrue(last > wrapped, "the row wraps");
    for (int line = wrapped; line <= last; line++) {
      int expected =
          intro
              + (line == wrapped ? TableColumns.PAD_ABOVE : 0)
              + (line == last ? TableColumns.PAD_BELOW : 0);
      assertEquals(expected, text.getLinePixel(line + 1) - text.getLinePixel(line), "line " + line);
      assertEquals(line == wrapped || line == last, text.getLine(line).endsWith(pad));
    }
  }
}
