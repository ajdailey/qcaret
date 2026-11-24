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
 * {@link TableWords}, {@link MarkdownTableLayout#fit(int[], int, boolean)}, {@link
 * MarkdownTableLayout#fit(int[], int, int, int)} and {@link TableWrap} over a fake measurer that
 * gives each char 10 pixels, 12 when strong.
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
  void aMeasuredBreakKeepsEachPartWithinItsColumn() {
    final RenderedMarkdown r = render("| h |\n|---|\n| aaaammmmaaaa |\n");
    TextMeasurer uneven =
        new TextMeasurer() {
          @Override
          public int width(int start, int end, boolean strong) {
            int width = 0;
            for (int i = start; i < end; i++) {
              width += r.text().charAt(i) == 'm' ? 30 : 10;
            }
            return strong ? width * 6 / 5 : width;
          }
        };
    int start = r.text().indexOf("aaaamm");
    int[] word = {start, start + 12, uneven.width(start, start + 12, false), 0};
    int[] proportional = TableWrap.breakCell(r.text(), word, 50);
    boolean over = false;
    for (int i = 0; i < proportional.length; i += 3) {
      over |= uneven.width(proportional[i], proportional[i + 1], false) > 50;
    }
    assertTrue(over, "a char-count break overruns the column on uneven chars");
    int[] measured = TableWrap.breakCell(r.text(), word, 50, uneven, false);
    StringBuilder joined = new StringBuilder();
    for (int i = 0; i < measured.length; i += 3) {
      int width = uneven.width(measured[i], measured[i + 1], false);
      assertTrue(width <= 50, "part " + i / 3 + " is " + width);
      assertEquals(width, measured[i + 2]);
      joined.append(r.text(), measured[i], measured[i + 1]);
    }
    assertEquals("aaaammmmaaaa", joined.toString());
    assertEquals(start + 4, measured[1], "the first part takes every char that fits");
  }

  /** Measures a table and fits it with a floor and the least padding. */
  private static MarkdownTableLayout floorFit(String markdown, int width, int floor) {
    RenderedMarkdown r = render(markdown);
    MarkdownTable table = r.tables().get(0);
    MarkdownTableLayout layout = new MarkdownTableLayout(table, r.text(), 13);
    layout.measure(FAKE, Integer.MAX_VALUE);
    TableWords words = new TableWords(table, r.text(), layout, MarkdownSpans.of(r, List.of()));
    words.measure(FAKE, Integer.MAX_VALUE);
    layout.fit(words.widest(), width, floor, 3);
    return layout;
  }

  @Test
  void thePaddingNarrowsBeforeACellWraps() {
    // Cells 70 and 70: 140 plus 4 x 13 is 192, plus 4 x 3 is 152.
    String table = "| Alpha | Bravo |\n|---|---|\n| one two | six ten |\n";
    MarkdownTableLayout narrowed = floorFit(table, 172, 12);
    assertEquals(8, narrowed.padding(), "(172 - 140) / 4");
    assertEquals(70, narrowed.columnWidth(0), "each column keeps its widest cell");
    assertEquals(70, narrowed.columnWidth(1));
  }

  @Test
  void thePaddingNarrowsBeforeAWordBreaks() {
    // Widest words 60 and 60 (strong "Alpha", "Bravo"), cells 110: 120 plus 4 x 13 is 172.
    String table = "| Alpha | Bravo |\n|---|---|\n| one two six | ten one two |\n";
    MarkdownTableLayout full = floorFit(table, 200, 12);
    assertEquals(13, full.padding());
    MarkdownTableLayout narrowed = floorFit(table, 160, 12);
    assertEquals(10, narrowed.padding(), "(160 - 120) / 4");
    assertEquals(60, narrowed.columnWidth(0), "each column keeps its widest word");
    assertEquals(60, narrowed.columnWidth(1));
    assertEquals(160, narrowed.edges()[2]);
  }

  /** Columns shared the room in proportion to their widest words and broke short ones. */
  @Test
  void aShortWordKeepsItsColumnBesideLongWords() {
    String table =
        "| a | b | c |\n|---|---|---|\n| xxxx | yyyyyyyyyyyyyyyyyyyy | zzzzzzzzzzzzzzzzzzzz |\n";
    // Widest words 40, 200 and 200; at 3 px padding the columns share 200 - 18 px.
    MarkdownTableLayout layout = floorFit(table, 200, 12);
    assertEquals(3, layout.padding());
    assertEquals(40, layout.columnWidth(0), "the short word fits");
    assertEquals(71, layout.columnWidth(1));
    assertEquals(71, layout.columnWidth(2));
    assertEquals(200, layout.edges()[3]);
  }

  @Test
  void aColumnNarrowsToTheFloorAndBelowItToFitTheWidth() {
    String table = "| Alpha | Bravo | Charlie |\n|---|---|---|\n| one | two | six |\n";
    // Widest words 60, 60 and 84; at 3 px padding the columns share 90 - 18 px.
    MarkdownTableLayout layout = floorFit(table, 90, 12);
    assertEquals(3, layout.padding());
    for (int c = 0; c < 3; c++) {
      assertEquals(24, layout.columnWidth(c), "column " + c);
    }
    assertEquals(90, layout.edges()[3]);
    // Three columns at the floor take 36 px: the padding goes, then the floor.
    MarkdownTableLayout floored = floorFit(table, 40, 12);
    assertEquals(0, floored.padding(), "(40 - 36) / 6");
    assertEquals(40, floored.edges()[3]);
    MarkdownTableLayout below = floorFit(table, 30, 12);
    assertEquals(0, below.padding());
    for (int c = 0; c < 3; c++) {
      assertEquals(10, below.columnWidth(c), "column " + c + " under the floor");
    }
    assertEquals(30, below.edges()[3], "never wider than the width");
  }

  /** A glyph wider than its column pushed the next cell's tab to a later stop. */
  @Test
  void aCellAfterAGlyphWiderThanItsColumnStartsAfterIt() {
    RenderedMarkdown r = render("| W | b |\n|---|---|\n| c | d |\n");
    MarkdownTable table = r.tables().get(0);
    MarkdownTableLayout layout = new MarkdownTableLayout(table, r.text(), 13);
    layout.measure(FAKE, Integer.MAX_VALUE);
    TableWords words = new TableWords(table, r.text(), layout, MarkdownSpans.of(r, List.of()));
    words.measure(FAKE, Integer.MAX_VALUE);
    layout.fit(words.widest(), 16, 12, 3);
    // Columns 8 px, no padding; the strong "W" is 12 px.
    assertEquals(0, layout.padding());
    TableWrap.Row header = TableWrap.rows(table, r.text(), layout, words, FAKE, 0, 1)[0];
    assertEquals(0, header.indents()[0]);
    assertArrayEquals(new int[] {13}, header.tabStops()[0], "after the 12 px glyph, not at 8");
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
