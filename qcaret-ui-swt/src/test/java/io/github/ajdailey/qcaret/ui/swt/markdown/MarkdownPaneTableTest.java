package io.github.ajdailey.qcaret.ui.swt.markdown;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.editor.MarkdownDisplayMap;
import io.github.ajdailey.qcaret.app.editor.MarkdownSpans;
import io.github.ajdailey.qcaret.app.editor.MarkdownTableLayout;
import io.github.ajdailey.qcaret.app.editor.TableWrap;
import io.github.ajdailey.qcaret.app.i18n.UiText;
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
import io.github.ajdailey.qcaret.ui.swt.ScaleSlack;
import io.github.ajdailey.qcaret.ui.swt.SwtUiDispatcher;
import io.github.ajdailey.qcaret.ui.swt.platform.PlatformTraits;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
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
 * controller's offsets and lines below the table, a refit on resize, a link in a wrapped cell, the
 * cell padding, and an eight-column table in a narrow pane.
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

  /** Eight columns whose header words do not fit a narrow pane. */
  private static final String EIGHT =
      String.join(
              "\n",
              "| Column one | Column two | Column three | Column four | Column five | Column six"
                  + " | Column seven | Column eight |",
              "|---|---|---|---|---|---|---|---|",
              "| alpha alpha alpha | beta beta beta | gamma gamma gamma | delta delta delta"
                  + " | epsilon epsilon | zeta zeta zeta | eta eta eta | theta theta theta |",
              "| 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 |",
              "")
          + "\nFiller.\n".repeat(40);

  /** Eight columns, four of them holding a word longer than 20 chars. */
  private static final String LONG_WORDS =
      String.join(
              "\n",
              "| Column one | Column two | Column three | Column four | Column five | Column six"
                  + " | Column seven | Column eight |",
              "|---|---|---|---|---|---|---|---|",
              "| alpha alpha alpha | beta beta beta | gamma gamma gamma | delta delta delta"
                  + " | epsilon epsilon | zeta zeta zeta | eta eta eta | theta theta theta |",
              "| Supercalifragilistic | Antidisestablishmentarianism | x | Pneumonoultramicroscopic"
                  + " | y | z | Floccinaucinihilipilification | w |",
              "")
          + "\nFiller.\n".repeat(40);

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

  private void await(BooleanSupplier condition, Supplier<String> what) {
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
  void aNarrowPaneKeepsEveryCellInsideItsColumn() {
    shell.setLayout(null);
    for (int width : new int[] {220, 300, 420, 560}) {
      pane.control().setBounds(0, 0, width, 300);
      flush();
      RenderedMarkdown r = show(EIGHT);
      if (pane.tables().noticed(0)) {
        // Wider fonts (cocoa) put 220 px below the floor: the notice test covers that case.
        assertEquals(220, width, "only the narrowest width shows the notice");
      } else {
        assertCellsInColumns(r.tables().get(0), width);
      }
    }
  }

  /**
   * Asserts that each display line of a table is one visual line and that each cell segment starts
   * and ends inside its column, and the table inside the pane.
   */
  private void assertCellsInColumns(MarkdownTable table, int width) {
    StyledText text = pane.text();
    int[] edges = pane.tables().edges(0);
    int left = text.getLeftMargin() - text.getHorizontalPixel();
    int right = text.getClientArea().width - text.getRightMargin();
    assertTrue(left + edges[edges.length - 1] <= right, "table inside the pane at " + width);
    int lastRow = table.firstLine() + table.rowCount() - 1;
    int first = map().displayLine(table.firstLine());
    int last = map().displayLine(lastRow) + map().displayLineCount(lastRow) - 1;
    String pad = String.valueOf(TableWrap.PAD);
    for (int line = first; line <= last; line++) {
      String shown = text.getLine(line);
      int offset = text.getOffsetAtLine(line);
      int next = text.getLinePixel(line + 1);
      String[] segments = shown.replace(pad, "").split("\t", -1);
      assertEquals(edges.length - 1, segments.length, "cells on line " + line);
      int at = offset;
      for (int c = 0; c < segments.length; c++) {
        if (!segments[c].isEmpty()) {
          Rectangle bounds = text.getTextBounds(at, at + segments[c].length() - 1);
          String where = "'" + segments[c] + "' at " + width + ": " + bounds;
          assertTrue(bounds.x >= left + edges[c], "starts in its column, " + where);
          assertTrue(
              bounds.x + bounds.width <= left + edges[c + 1], "ends in its column, " + where);
          assertTrue(bounds.y + bounds.height <= next, "one visual line, " + where);
        }
        at += segments[c].length() + 1;
      }
    }
  }

  /**
   * A pane narrower than eight columns at the floor showed glyphs across the column lines and the
   * first column's letters twice (Windows, Split at 420 px).
   */
  @Test
  void aTableTooWideForThePaneShowsANoticeUntilThePaneWidens() {
    shell.setLayout(null);
    pane.control().setBounds(0, 0, 120, 300);
    flush();
    RenderedMarkdown r = show(EIGHT);
    MarkdownTable table = r.tables().get(0);
    StyledText text = pane.text();
    int header = table.firstLine();
    assertTrue(pane.tables().noticed(0), "noticed at 120 px");
    assertEquals(0, pane.tables().edges(0).length, "no borders");
    int notice = map().displayLine(header);
    assertEquals(
        UiText.MARKDOWN_TABLE_TOO_WIDE.text() + TableWrap.PAD, text.getLine(notice), "notice line");
    int noticeStart = text.getOffsetAtLine(notice);
    StyleRange style =
        pane.styler()
            .style(notice, noticeStart, noticeStart + text.getLine(notice).length())
            .styles[0];
    assertTrue((style.fontStyle & SWT.ITALIC) != 0, "italic: " + style);
    for (int row = 1; row < table.rowCount(); row++) {
      assertEquals(0, map().displayLineCount(header + row), "row " + row + " shows nothing");
      assertEquals(notice, map().displayLine(header + row), "row " + row + " maps to the notice");
    }
    assertEquals(r.lineCount() - (table.rowCount() - 1), text.getLineCount());
    assertEquals(map().text(), text.getText());
    int after = header + table.rowCount() + 1;
    assertTrue(text.getLine(map().displayLine(after)).startsWith("Filler"));

    int cell = r.text().indexOf("gamma");
    pane.selectRendered(cell, cell + 5);
    assertEquals("", pane.text().getSelectionText(), "Find in a noticed row selects nothing");
    pane.scrollToLine(header + 2);
    flush();
    assertEquals(notice, text.getTopIndex(), "Sync Scrolling to a row lands on the notice");
    int start = text.getOffsetAtLine(notice);
    text.setSelection(start, start + text.getLine(notice).length());
    assertEquals("", pane.selectedRendered(), "the notice copies nothing");

    text.setSelection(text.getOffsetAtLine(map().displayLine(after)));
    pane.scrollToLine(after);
    flush();
    assertEquals(map().displayLine(after), text.getTopIndex(), "the top line at 120 px");
    pane.control().setSize(700, 300);
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !pane.tables().noticed(0) && pane.tables().laidOut(0) && !pane.tables().busy();
          }
        },
        "the table returns at 700 px");
    flush();
    assertTrue(pane.tables().edges(0).length > 0);
    for (int row = 0; row < table.rowCount(); row++) {
      assertTrue(map().displayLineCount(header + row) >= 1, "row " + row);
    }
    assertEquals(map().text(), text.getText());
    assertEquals(map().displayLine(after), text.getTopIndex(), "the top line kept");
    assertCellsInColumns(table, 700);
  }

  /** Short words broke mid-word while the columns of long words kept the room (Windows, 100 %). */
  @Test
  void aWordShorterThanTheBreakingColumnsNeverBreaks() {
    shell.setLayout(null);
    pane.control().setBounds(0, 0, 700, 300);
    flush();
    RenderedMarkdown r = show(LONG_WORDS);
    MarkdownTable table = r.tables().get(0);
    StyledText text = pane.text();
    String pad = String.valueOf(TableWrap.PAD);
    List<String> broken = new ArrayList<String>();
    for (int row = 0; row < table.rowCount(); row++) {
      int line = table.firstLine() + row;
      int first = map().displayLine(line);
      List<String[]> lines = new ArrayList<String[]>();
      for (int j = 0; j < map().displayLineCount(line); j++) {
        lines.add(text.getLine(first + j).replace(pad, "").split("\t", -1));
      }
      int[] cells = table.cells()[row];
      for (int c = 0; 2 * c + 1 < cells.length; c++) {
        String cell = r.text().substring(cells[2 * c], cells[2 * c + 1]);
        broken.addAll(brokenWords(cell, lines, c));
      }
    }
    assertTrue(broken.size() > 0, "the long words break");
    for (String word : broken) {
      assertTrue(word.length() >= 20, "broken: " + word + " in " + broken);
    }
  }

  /** Returns the words of a cell that its segments on successive display lines break inside. */
  private static List<String> brokenWords(String cell, List<String[]> lines, int column) {
    List<String> broken = new ArrayList<String>();
    int at = 0;
    for (String[] segments : lines) {
      String segment = column < segments.length ? segments[column] : "";
      if (segment.isEmpty()) {
        continue;
      }
      while (at < cell.length() && cell.charAt(at) == ' ') {
        at++;
      }
      assertTrue(cell.startsWith(segment, at), "'" + segment + "' at " + at + " of " + cell);
      at += segment.length();
      if (at < cell.length() && cell.charAt(at) != ' ' && cell.charAt(at - 1) != ' ') {
        int start = cell.lastIndexOf(' ', at - 1) + 1;
        int end = cell.indexOf(' ', at);
        broken.add(cell.substring(start, end < 0 ? cell.length() : end));
      }
    }
    return broken;
  }

  /** At 50 % zoom cells wrapped where a narrower padding kept each on one line (Windows). */
  @Test
  void thePaddingNarrowsBeforeACellWraps() {
    shell.setLayout(null);
    pane.control().setBounds(0, 0, 1600, 300);
    flush();
    pane.setZoom(50);
    RenderedMarkdown r = show(EIGHT);
    MarkdownTable table = r.tables().get(0);
    MarkdownTableLayout layout = pane.tables().measured(0);
    int cells = 0;
    for (int c = 0; c < table.alignments().size(); c++) {
      int widest = 0;
      for (int row = 0; row < table.rowCount(); row++) {
        widest = Math.max(widest, layout.width(row, c));
      }
      cells += widest;
    }
    StyledText text = pane.text();
    int textWidth = text.getClientArea().width - text.getLeftMargin() - text.getRightMargin();
    int columns = table.alignments().size();
    // Between the cells at the least padding, 2 px, and at the full padding, 7 px.
    int target = cells + 2 * columns * 4;
    pane.control().setBounds(0, 0, 1600 - textWidth + target, 300);
    flush();
    show(EIGHT);
    for (int row = 0; row < table.rowCount(); row++) {
      assertEquals(
          1, map().displayLineCount(table.firstLine() + row), "row " + row + ": " + map().text());
    }
    int[] edges = pane.tables().edges(0);
    assertTrue(edges[edges.length - 1] <= target, "table inside " + target);
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
    resize(700, 450, true);
  }

  @Test
  void aWiderPaneWrapsLessAndKeepsTheTopLine() {
    resize(450, 700, false);
  }

  /**
   * Resizes the pane from {@code from} px to {@code width} px with "Paragraph after" at the top.
   * The test sets the pane control's size inside the shell, not the shell's: SWT applies a child's
   * size at once, while a shell's size waits for the display server, which applies it late or not
   * at all on some hosts.
   */
  private void resize(int from, int width, final boolean narrower) {
    final StyledText text = pane.text();
    shell.setLayout(null);
    pane.control().setBounds(0, 0, from, 300);
    flush();
    RenderedMarkdown r = show(DOCUMENT);
    int after = r.text().indexOf("Paragraph after");
    int line = r.lineCount() - 1;
    for (int i = 0; i < r.lineCount(); i++) {
      if (r.lineStart(i) == after) {
        line = i;
      }
    }
    final int starting = text.getClientArea().width;
    final int row = r.tables().get(0).firstLine() + 1;
    final int lines = map().displayLineCount(row);
    pane.scrollToLine(line);
    flush();
    pane.control().setSize(width, 300);
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            int now = map().displayLineCount(row);
            return (narrower ? now > lines : now < lines) && !pane.tables().busy();
          }
        },
        new Supplier<String>() {
          @Override
          public String get() {
            return (narrower ? "narrower wraps more" : "wider wraps less")
                + ": pane "
                + starting
                + " -> "
                + pane.text().getClientArea().width
                + " px, row lines "
                + lines
                + " -> "
                + map().displayLineCount(row);
          }
        });
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
    int slack = ScaleSlack.of(text);
    assertEquals(intro + PadGlyphs.PAD_ABOVE + PadGlyphs.PAD_BELOW, height, slack, "one-line row");
    String pad = String.valueOf(TableWrap.PAD);
    assertTrue(last > wrapped, "the row wraps");
    for (int line = wrapped; line <= last; line++) {
      int expected =
          intro
              + (line == wrapped ? PadGlyphs.PAD_ABOVE : 0)
              + (line == last ? PadGlyphs.PAD_BELOW : 0);
      assertEquals(
          expected, text.getLinePixel(line + 1) - text.getLinePixel(line), slack, "line " + line);
      assertEquals(line == wrapped || line == last, text.getLine(line).endsWith(pad));
    }
  }
}
