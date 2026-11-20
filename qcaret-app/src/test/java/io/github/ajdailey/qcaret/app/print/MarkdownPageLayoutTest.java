package io.github.ajdailey.qcaret.app.print;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.ajdailey.qcaret.app.editor.MarkdownTableLayout;
import io.github.ajdailey.qcaret.app.view.MarkdownImageRequest;
import io.github.ajdailey.qcaret.app.view.MarkdownPrintSheet;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.markdown.MarkdownRenderer;
import io.github.ajdailey.qcaret.core.markdown.RenderedMarkdown;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/** Pagination of rendered Markdown over a fake measurer: 10 pixels a row unless a test says. */
class MarkdownPageLayoutTest {
  private static final CancellationToken GO = new CancellationToken();

  /** Measures every cell at 10 pixels a char and every line as one 10 pixel row. */
  private static final class FakeSheet implements MarkdownPrintSheet {
    final int height;
    final Map<Integer, int[]> rows = new HashMap<Integer, int[]>();

    FakeSheet(int height) {
      this.height = height;
    }

    @Override
    public Body body() {
      return new Body(500, height, 4);
    }

    @Override
    public int width(String text, boolean strong) {
      return text.length() * 10;
    }

    @Override
    public void image(MarkdownImageRequest request, byte[] bytes) {
      // Sizes come from rows.
    }

    @Override
    public int[] rows(int line, MarkdownTableLayout.Row table) {
      int[] heights = rows.get(line);
      return heights == null ? new int[] {10} : heights;
    }

    @Override
    public void page(Page page) {
      // Nothing prints.
    }
  }

  private static RenderedMarkdown render(String markdown) {
    return new MarkdownRenderer().render(markdown, null, GO);
  }

  /** Lists each page's placed lines as {@code line:firstRow+rowCount@y}. */
  private static List<List<String>> pages(MarkdownPageLayout layout) {
    List<List<String>> out = new ArrayList<List<String>>();
    for (int n = 1; n <= layout.pageCount(); n++) {
      List<String> page = new ArrayList<String>();
      for (MarkdownPrintSheet.Placed p : layout.page(n).lines()) {
        page.add(p.line() + ":" + p.firstRow() + "+" + p.rowCount() + "@" + p.y());
      }
      out.add(page);
    }
    return out;
  }

  @Test
  void aParagraphLineBreaksBetweenItsRows() {
    FakeSheet sheet = new FakeSheet(30);
    sheet.rows.put(0, new int[] {10, 10, 10, 10, 10});
    MarkdownPageLayout layout = MarkdownPageLayout.of(render("mock paragraph\n"), sheet, GO);
    assertEquals(List.of(List.of("0:0+3@0"), List.of("0:3+2@0")), pages(layout));
  }

  @Test
  void aCodeLineDoesNotSplitAndMovesWhole() {
    FakeSheet sheet = new FakeSheet(30);
    sheet.rows.put(2, new int[] {10, 10});
    RenderedMarkdown r = render("text\n\n```\nmock code line\n```\n");
    MarkdownPageLayout layout = MarkdownPageLayout.of(r, sheet, GO);
    assertEquals(List.of(List.of("0:0+1@0", "1:0+1@10"), List.of("2:0+2@0")), pages(layout));
  }

  @Test
  void aTableRowDoesNotSplitAndTheHeaderRepeatsOnEachPage() {
    FakeSheet sheet = new FakeSheet(30);
    String table = "| a | b |\n|---|---|\n| 1 | 2 |\n| 3 | 4 |\n| 5 | 6 |\n| 7 | 8 |\n| 9 | 0 |\n";
    MarkdownPageLayout layout = MarkdownPageLayout.of(render(table), sheet, GO);
    assertEquals(
        List.of(
            List.of("0:0+1@0", "1:0+1@10", "2:0+1@20"),
            List.of("0:0+1@0", "3:0+1@10", "4:0+1@20"),
            List.of("0:0+1@0", "5:0+1@10")),
        pages(layout));
    MarkdownPrintSheet.Placed header = layout.page(2).lines().get(0);
    assertNotNull(header.table());
    assertEquals(List.of(0, 18, 36), toList(header.edges()));
    assertEquals(4, header.table().indent());
  }

  private static List<Integer> toList(int[] values) {
    List<Integer> out = new ArrayList<Integer>();
    for (int v : values) {
      out.add(v);
    }
    return out;
  }

  @Test
  void aHeadingNeverEndsAPage() {
    FakeSheet sheet = new FakeSheet(30);
    MarkdownPageLayout layout =
        MarkdownPageLayout.of(render("first\n\n# Mock heading\n\nsecond\n"), sheet, GO);
    assertEquals(
        List.of(List.of("0:0+1@0", "1:0+1@10"), List.of("2:0+1@0", "3:0+1@10", "4:0+1@20")),
        pages(layout));
  }

  @Test
  void aHeadingFirstOnItsPageStaysThere() {
    FakeSheet sheet = new FakeSheet(20);
    sheet.rows.put(2, new int[] {10, 10});
    MarkdownPageLayout layout =
        MarkdownPageLayout.of(render("# Mock heading\n\nlong paragraph\n"), sheet, GO);
    assertEquals(List.of(List.of("0:0+1@0", "1:0+1@10"), List.of("2:0+2@0")), pages(layout));
  }

  @Test
  void separatorLinesAtTheTopOfAPageAreLeftOut() {
    FakeSheet sheet = new FakeSheet(10);
    MarkdownPageLayout layout = MarkdownPageLayout.of(render("one\n\ntwo\n"), sheet, GO);
    assertEquals(List.of(List.of("0:0+1@0"), List.of("2:0+1@0")), pages(layout));
    assertNull(layout.page(1).lines().get(0).table());
  }

  @Test
  void aLineThatDoesNotSplitAndIsTallerThanThePageBreaksBetweenItsRows() {
    FakeSheet sheet = new FakeSheet(30);
    sheet.rows.put(0, new int[] {10, 10, 10, 10, 10});
    MarkdownPageLayout layout = MarkdownPageLayout.of(render("```\nmock\n```\n"), sheet, GO);
    assertEquals(List.of(List.of("0:0+3@0"), List.of("0:3+2@0")), pages(layout));
  }

  @Test
  void aRowTallerThanThePageTakesAPageOfItsOwn() {
    FakeSheet sheet = new FakeSheet(30);
    sheet.rows.put(2, new int[] {50});
    MarkdownPageLayout layout =
        MarkdownPageLayout.of(render("one\n\n![mock](mock.png)\n\ntwo\n"), sheet, GO);
    assertEquals(
        List.of(List.of("0:0+1@0", "1:0+1@10"), List.of("2:0+1@0"), List.of("4:0+1@0")),
        pages(layout));
  }

  @Test
  void pagesCarryTheirNumberAndTheWholeCount() {
    FakeSheet sheet = new FakeSheet(10);
    MarkdownPageLayout layout = MarkdownPageLayout.of(render("a\n\nb\n\nc\n"), sheet, GO);
    assertEquals(3, layout.pageCount());
    assertEquals(2, layout.page(2).number());
    assertEquals(3, layout.page(2).total());
  }

  @Test
  void anEmptyDocumentHasOnePage() {
    MarkdownPageLayout layout = MarkdownPageLayout.of(render(""), new FakeSheet(30), GO);
    assertEquals(1, layout.pageCount());
  }

  @Test
  void aCanceledLayoutStops() {
    final CancellationToken cancel = new CancellationToken();
    cancel.cancel();
    final RenderedMarkdown r = render("one\n");
    assertThrows(
        CancellationException.class,
        new Executable() {
          @Override
          public void execute() {
            MarkdownPageLayout.of(r, new FakeSheet(30), cancel);
          }
        });
  }
}
