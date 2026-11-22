package io.github.ajdailey.qcaret.app.editor;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.markdown.MarkdownRenderer;
import io.github.ajdailey.qcaret.core.markdown.MarkdownTable;
import io.github.ajdailey.qcaret.core.markdown.RenderedMarkdown;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link TableWords}, {@link MarkdownTableLayout#fit(int[], int, boolean)} and {@link TableWrap}
 * over a fake measurer that gives each char 10 pixels, 12 when strong.
 */
class TableWrapTest {
  static final int PADDING = 4;

  /** Ten pixels a char, twelve in a strong row. */
  static final TextMeasurer FAKE =
      new TextMeasurer() {
        @Override
        public int width(int start, int end, boolean strong) {
          return (end - start) * (strong ? 12 : 10);
        }
      };

  static RenderedMarkdown render(String text) {
    return new MarkdownRenderer().render(text, null, new CancellationToken());
  }

  /** Measures the first table, fits it to a width and lays out every row. */
  static TableWrap.Row[] wrap(RenderedMarkdown r, int width) {
    MarkdownTable table = r.tables().get(0);
    MarkdownTableLayout layout = new MarkdownTableLayout(table, r.text(), PADDING);
    layout.measure(FAKE, Integer.MAX_VALUE);
    TableWords words = new TableWords(table, r.text(), layout, MarkdownSpans.of(r, List.of()));
    words.measure(FAKE, Integer.MAX_VALUE);
    layout.fit(words.widest(), width, true);
    return TableWrap.rows(table, r.text(), layout, words, 0, table.rowCount());
  }

  private static String pad(String line) {
    return line + TableWrap.PAD;
  }

  @Test
  void aRowThatFitsIsOneLineStartingWithThePad() {
    RenderedMarkdown r = render("| a | b |\n|---|---|\n| cc | d |\n");
    TableWrap.Row[] rows = wrap(r, 1_000);
    assertEquals(pad("a\tb"), rows[0].text());
    assertEquals(pad("cc\td"), rows[1].text());
    assertEquals(1, rows[1].lines());
    assertEquals(PADDING, rows[1].indents()[0]);
    assertArrayEquals(new int[] {20 + 2 * PADDING + PADDING}, rows[1].tabStops()[0]);
  }

  @Test
  void aLongCellBreaksAtItsWordsAndItsContinuationStartsAtItsColumn() {
    RenderedMarkdown r = render("| Name | Note |\n|---|---:|\n| x | one two three |\n");
    // Measured 48 and 130; widest words 48 and 50; room 140 - 16 = 124 gives column 2 the 26
    // spare pixels: 76.
    TableWrap.Row[] rows = wrap(r, 140);
    TableWrap.Row row = rows[1];
    assertEquals(2, row.lines());
    assertEquals(pad("x\tone two") + "\n" + pad("\tthree"), row.text());
    int column2 = 48 + 2 * PADDING;
    // Right-aligned in 76 pixels: "one two" is 70, "three" 50.
    assertEquals(column2 + PADDING + 6, row.tabStops()[0][0]);
    assertEquals(column2 + PADDING + 26, row.tabStops()[1][0]);
    assertEquals(PADDING, row.indents()[1]);
  }

  @Test
  void aWordWiderThanItsColumnBreaksInsideIt() {
    RenderedMarkdown r = render("| a | b |\n|---|---|\n| abcdefghij | k |\n");
    TableWrap.Row[] rows = wrap(r, 2 * 4 * PADDING + 50);
    TableWrap.Row row = rows[1];
    assertTrue(row.lines() > 1, row.text());
    assertFalse(row.text().contains("abcdefghij"));
    assertEquals("abcdefghij", row.text().replaceAll("[​\t\nk]", ""));
  }

  @Test
  void aBoundedFitNeverExceedsTheWidth() {
    RenderedMarkdown r = render("| aaaaaaaaaa | bbbbbbbbbb |\n|---|---|\n| c | d |\n");
    MarkdownTable table = r.tables().get(0);
    MarkdownTableLayout layout = new MarkdownTableLayout(table, r.text(), PADDING);
    layout.measure(FAKE, Integer.MAX_VALUE);
    layout.fit(new int[] {120, 120}, 116, true);
    int[] edges = layout.edges();
    assertEquals(116, edges[2]);
    layout.fit(new int[] {120, 120}, 116, false);
    assertEquals(256, layout.edges()[2], "unbounded keeps the widest words");
    layout.fit(new int[] {120, 120}, 1_000, true);
    assertEquals(256, layout.edges()[2], "a wider width restores the measured widths");
  }

  @Test
  void wordsCarryTheirWidthsAndTheSpacesBefore() {
    RenderedMarkdown r = render("| h |\n|---|\n| one  two |\n| solo |\n");
    MarkdownTable table = r.tables().get(0);
    MarkdownTableLayout layout = new MarkdownTableLayout(table, r.text(), PADDING);
    layout.measure(FAKE, Integer.MAX_VALUE);
    TableWords words = new TableWords(table, r.text(), layout, MarkdownSpans.of(r, List.of()));
    assertEquals(1, words.measure(FAKE, 1));
    assertFalse(words.measured());
    words.measure(FAKE, 10);
    assertTrue(words.measured());
    int[] cell = words.words(1, 0);
    String text = r.text();
    int start = table.cells()[1][0];
    assertEquals("one", text.substring(start + cell[0], start + cell[1]));
    assertEquals(30, cell[2]);
    assertEquals(0, cell[3]);
    assertEquals(30, cell[6]);
    assertEquals(10 * (cell[4] - cell[1]), cell[7]);
    assertEquals(0, words.words(2, 0).length);
    assertArrayEquals(new int[] {40}, words.widest());
  }
}
