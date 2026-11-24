package io.github.ajdailey.qcaret.app.editor;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.core.markdown.MarkdownTable;
import io.github.ajdailey.qcaret.core.markdown.RenderedMarkdown;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link MarkdownDisplayMap} round trips between the display and the rendered text over every table
 * shape, laid out by {@link TableWrap} with {@link TableWrapTest#FAKE} widths, a paragraph before
 * and after each table.
 */
class MarkdownDisplayMapTest {
  /** Table shapes, each with a width that makes it fit, wrap or break. */
  private static final String[][] SHAPES = {
    {"fits", "| a | b |\n|---|---|\n| cc | d |\n", "1000"},
    {"one column wraps", "| Name | Note |\n|---|---:|\n| x | one two three |\n", "140"},
    {
      "several columns wrap",
      "| A | B | C |\n|---|:-:|---|\n| one two three | four five six | x |\n"
          + "| y | z | seven eight |\n",
      "200"
    },
    {"word wider than its column", "| a | b |\n|---|---|\n| abcdefghij | k |\n", "82"},
    {"empty cells", "| a | b | c |\n|---|---|---|\n|  | two words |  |\n| x |\n", "100"},
    {"inline code", "| `code span here` | b |\n|---|---|\n| *one* **two** | `x y` |\n", "120"},
  };

  private static RenderedMarkdown render(String[] shape) {
    return TableWrapTest.render("Before.\n\n" + shape[1] + "\nAfter.\n");
  }

  private static MarkdownDisplayMap mapped(RenderedMarkdown r, int width) {
    MarkdownDisplayMap map = new MarkdownDisplayMap(r);
    MarkdownTable table = r.tables().get(0);
    MarkdownDisplayMap.Change change = map.replace(table.firstLine(), TableWrapTest.wrap(r, width));
    assertEquals(
        map.text(),
        r.text().substring(0, change.start())
            + change.text()
            + r.text().substring(change.start() + change.length()));
    return map;
  }

  /** The display offset a display line starts at. */
  private static int lineStart(String text, int line) {
    int at = 0;
    for (int i = 0; i < line; i++) {
      at = text.indexOf('\n', at) + 1;
    }
    return at;
  }

  @Test
  void everyCopiedCharMapsBothWaysAndTheRestIsLayout() {
    for (String[] shape : SHAPES) {
      RenderedMarkdown r = render(shape);
      MarkdownDisplayMap map = mapped(r, Integer.parseInt(shape[2]));
      String display = map.text();
      String rendered = r.text();
      boolean[] copied = new boolean[rendered.length()];
      boolean[] shown = new boolean[display.length()];
      for (int line = 0; line < map.lineCount(); line++) {
        int[] pieces = map.pieces(line);
        for (int k = 0; k < pieces.length; k += 3) {
          for (int i = 0; i < pieces[k + 2]; i++) {
            int d = pieces[k] + i;
            int at = pieces[k + 1] + i;
            assertEquals(rendered.charAt(at), display.charAt(d), shape[0] + " char " + d);
            assertEquals(at, map.rendered(d), shape[0] + " rendered " + d);
            assertEquals(d, map.display(at), shape[0] + " display " + at);
            copied[at] = true;
            shown[d] = true;
          }
        }
      }
      for (int i = 0; i < rendered.length(); i++) {
        char c = rendered.charAt(i);
        assertTrue(copied[i] || c == ' ' || c == '\n', shape[0] + " rendered " + i + " left out");
        if (c == '\n') {
          assertEquals(i, map.rendered(map.display(i)), shape[0] + " line break " + i);
        }
      }
      for (int d = 0; d < display.length(); d++) {
        char c = display.charAt(d);
        assertTrue(
            shown[d] || c == TableWrap.PAD || c == '\t' || c == '\n',
            shape[0] + " display " + d + " is " + c);
      }
      assertTrue(display.startsWith("Before.") && display.endsWith("After."), shape[0]);
    }
  }

  @Test
  void linesMapToTheirRowAndBack() {
    for (String[] shape : SHAPES) {
      RenderedMarkdown r = render(shape);
      MarkdownDisplayMap map = mapped(r, Integer.parseInt(shape[2]));
      int lines = 0;
      for (int line = 0; line < r.lineCount(); line++) {
        int first = map.displayLine(line);
        assertEquals(lines, first, shape[0] + " line " + line);
        for (int sub = 0; sub < map.displayLineCount(line); sub++) {
          assertEquals(line, map.renderedLine(first + sub), shape[0]);
          assertEquals(sub, map.subLine(first + sub), shape[0]);
        }
        lines += map.displayLineCount(line);
      }
      assertEquals(lines, map.lineCount(), shape[0]);
      assertEquals(lines, map.text().split("\n", -1).length, shape[0]);
    }
  }

  @Test
  void aRangeOverAWrappedRowIsTheRenderedRow() {
    RenderedMarkdown r = render(SHAPES[2]);
    MarkdownDisplayMap map = mapped(r, 200);
    int line = r.tables().get(0).firstLine() + 1;
    int first = map.displayLine(line);
    int count = map.displayLineCount(line);
    assertTrue(count > 1);
    String display = map.text();
    int start = lineStart(display, first);
    int end = lineStart(display, first + count) - 1;
    assertArrayEquals(
        new int[] {r.lineStart(line), r.lineStart(line + 1) - 1}, map.renderedRange(start, end));
    int word = display.indexOf("five");
    int at = r.text().indexOf("five");
    assertArrayEquals(new int[] {at, at + 4}, map.renderedRange(word, word + 4));
    assertArrayEquals(new int[] {0, r.text().length()}, map.renderedRange(0, display.length()));
  }

  @Test
  void aCodeLinesLeadingSpacesShowAsNoBreakSpaces() {
    RenderedMarkdown r = TableWrapTest.render("Text  with spaces.\n\n```\n  two  more\n\tx\n```\n");
    MarkdownDisplayMap map = new MarkdownDisplayMap(r, new int[] {1});
    String indent = String.valueOf(MarkdownDisplayMap.INDENT).repeat(2);
    int code = r.codeBlocks().get(0).firstLine();
    assertEquals(
        indent + "two  more", map.text().substring(map.lineStart(code), map.lineEnd(code)));
    assertEquals("\tx", map.text().substring(map.lineStart(code + 1), map.lineEnd(code + 1)));
    assertEquals("Text  with spaces.", map.text().substring(0, map.lineEnd(0)));
    for (int offset = 0; offset < r.text().length(); offset++) {
      assertEquals(offset, map.rendered(map.display(offset)));
    }
  }

  @Test
  void aNoticeShowsATableAsOneLineAndMapsEveryRowToIt() {
    RenderedMarkdown r = render(SHAPES[2]);
    MarkdownTable table = r.tables().get(0);
    int header = table.firstLine();
    int rows = table.rowCount();
    MarkdownDisplayMap map = new MarkdownDisplayMap(r);
    MarkdownDisplayMap.Change change = map.replace(header, TableWrap.notice("mock notice", rows));
    String noticeLine = "mock notice" + TableWrap.PAD;
    String expected =
        r.text().substring(0, r.lineStart(header))
            + noticeLine
            + r.text().substring(r.lineStart(header + rows) - 1);
    assertEquals(expected, map.text());
    assertEquals(
        map.text(),
        r.text().substring(0, change.start())
            + change.text()
            + r.text().substring(change.start() + change.length()));
    assertEquals(r.lineCount() - (rows - 1), map.lineCount());
    for (int row = 0; row < rows; row++) {
      assertEquals(header, map.displayLine(header + row));
      assertEquals(row == 0 ? 1 : 0, map.displayLineCount(header + row));
    }
    assertEquals(header + rows, map.renderedLine(header + 1), "the line after the table");
    assertEquals(header + 1, map.displayLine(header + rows));
    int after = r.text().indexOf("After.");
    assertEquals(after, map.rendered(map.display(after)));
    for (int offset = 0; offset <= r.text().length(); offset++) {
      int shown = map.display(offset);
      assertTrue(shown >= 0 && shown <= map.text().length(), "offset " + offset);
      map.rendered(shown);
    }
    int[] range = map.renderedRange(map.lineStart(header), map.lineEnd(header));
    assertEquals(range[0], range[1], "the notice copies nothing");

    // Back in steps, as a layout shows rows, and at once.
    TableWrap.Row[] wrapped = TableWrapTest.wrap(r, 200);
    map.replace(header, Arrays.copyOfRange(wrapped, 0, 1));
    map.replace(header + 1, Arrays.copyOfRange(wrapped, 1, rows));
    MarkdownDisplayMap fresh = mapped(r, 200);
    assertEquals(fresh.text(), map.text());
    assertEquals(fresh.lineCount(), map.lineCount());
    map.replace(header, TableWrap.notice("mock notice", rows));
    map.replace(header, wrapped);
    assertEquals(fresh.text(), map.text());
    for (int offset = 0; offset <= r.text().length(); offset++) {
      assertEquals(fresh.display(offset), map.display(offset), "offset " + offset);
    }
  }

  @Test
  void aReplaceWithNullRowsRestoresTheRenderedText() {
    RenderedMarkdown r = render(SHAPES[1]);
    MarkdownDisplayMap map = mapped(r, 140);
    MarkdownTable table = r.tables().get(0);
    map.replace(table.firstLine(), new TableWrap.Row[table.rowCount()]);
    assertEquals(r.text(), map.text());
    assertNull(map.row(table.firstLine()));
    assertEquals(r.lineCount(), map.lineCount());
  }

  @Test
  void paddedSeparatorsMapBothWaysBesideAWrappedTable() {
    for (String[] shape : SHAPES) {
      RenderedMarkdown r = render(shape);
      List<Integer> separators = new ArrayList<Integer>();
      for (int line = 0; line < r.lineCount(); line++) {
        if (BlockSpacing.separator(r, line)) {
          separators.add(line);
        }
      }
      int[] padded = new int[separators.size()];
      for (int i = 0; i < padded.length; i++) {
        padded[i] = separators.get(i);
      }
      assertEquals(2, padded.length, shape[0]);
      MarkdownDisplayMap map = new MarkdownDisplayMap(r, padded);
      MarkdownTable table = r.tables().get(0);
      map.replace(table.firstLine(), TableWrapTest.wrap(r, Integer.parseInt(shape[2])));
      String display = map.text();
      for (int line : padded) {
        assertTrue(map.padded(line), shape[0]);
        assertEquals(0, map.pieces(map.displayLine(line)).length, shape[0]);
        assertEquals(TableWrap.PAD, display.charAt(map.lineStart(line)), shape[0]);
        assertEquals(map.lineStart(line) + 1, map.lineEnd(line), shape[0]);
      }
      int tableStart = r.lineStart(table.firstLine());
      int tableEnd = r.lineStart(table.firstLine() + table.rowCount()) - 1;
      for (int i = 0; i <= r.text().length(); i++) {
        if (i < tableStart || i >= tableEnd) {
          assertEquals(i, map.rendered(map.display(i)), shape[0] + " offset " + i);
        }
      }
      assertArrayEquals(
          new int[] {0, r.text().length()}, map.renderedRange(0, display.length()), shape[0]);
      assertEquals(display.split("\n", -1).length, map.lineCount(), shape[0]);
      assertTrue(display.startsWith("Before.\n" + TableWrap.PAD + "\n"), shape[0]);
    }
  }

  /** Find across a wrapped cell's break selected other cells' segments between its ends. */
  @Test
  void aRangeAcrossACellBreakSelectsItsFirstLine() {
    RenderedMarkdown r = render(SHAPES[2]);
    MarkdownDisplayMap map = mapped(r, 170);
    String display = map.text();
    int start = r.text().indexOf("one two");
    int end = start + "one two".length();
    int from = map.display(start);
    assertTrue(display.substring(from, map.display(end)).contains("\n"), "the cell wraps");
    int[] range = map.displayRange(start, end);
    assertEquals(from, range[0]);
    assertEquals("one", display.substring(range[0], range[1]));
    // "three" is on the cell's third line, "four" on the row's first.
    int across = r.text().indexOf("three");
    int after = r.text().indexOf("four") + 4;
    assertArrayEquals(
        new int[] {map.display(after), map.display(across)},
        map.displayRange(across, after),
        "a range over two cells is not cut");
    int five = r.text().indexOf("five");
    assertArrayEquals(
        new int[] {map.display(five), map.display(five + 4)}, map.displayRange(five, five + 4));
  }

  /**
   * Rows replaced a few at a time, out of order and back to rendered, between padded separators,
   * index and text the same as one replace of the final rows: a replace copies the rows before it
   * and shifts the rows after it.
   */
  @Test
  void stepwiseReplacesMatchOneReplace() {
    for (String[] shape : SHAPES) {
      RenderedMarkdown r = render(shape);
      List<Integer> separators = new ArrayList<Integer>();
      for (int line = 0; line < r.lineCount(); line++) {
        if (BlockSpacing.separator(r, line)) {
          separators.add(line);
        }
      }
      int[] padded = {separators.get(0), separators.get(1)};
      MarkdownTable table = r.tables().get(0);
      TableWrap.Row[] rows = TableWrapTest.wrap(r, Integer.parseInt(shape[2]));
      MarkdownDisplayMap steps = new MarkdownDisplayMap(r, padded);
      int first = table.firstLine();
      for (int k = rows.length - 1; k >= 0; k -= 2) {
        int from = Math.max(0, k - 1);
        steps.replace(first + from, Arrays.copyOfRange(rows, from, k + 1));
        assertEquals('B', steps.charAt(0), shape[0]);
      }
      steps.replace(first, new TableWrap.Row[] {null});
      steps.replace(first, new TableWrap.Row[] {rows[0]});
      MarkdownDisplayMap once = new MarkdownDisplayMap(r, padded);
      once.replace(first, rows);
      assertEquals(once.text(), steps.text(), shape[0]);
      assertEquals(once.lineCount(), steps.lineCount(), shape[0]);
      for (int line = 0; line < r.lineCount(); line++) {
        assertEquals(once.displayLine(line), steps.displayLine(line), shape[0] + " line " + line);
        assertEquals(once.lineStart(line), steps.lineStart(line), shape[0] + " start " + line);
        assertEquals(once.lineEnd(line), steps.lineEnd(line), shape[0] + " end " + line);
      }
      for (int d = 0; d <= once.text().length(); d++) {
        assertEquals(once.rendered(d), steps.rendered(d), shape[0] + " offset " + d);
      }
    }
  }
}
