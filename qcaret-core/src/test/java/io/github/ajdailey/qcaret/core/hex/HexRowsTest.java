package io.github.ajdailey.qcaret.core.hex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

class HexRowsTest {
  @Test
  void aSixteenByteRowLaysOutAsTheDesignShows() {
    HexRows rows = new HexRows(100, 16);
    String header = "Offset    00 01 02 03 04 05 06 07  08 09 0A 0B 0C 0D 0E 0F  0123456789ABCDEF";
    assertEquals(header.indexOf("00 01"), rows.hexColumn(0));
    assertEquals(header.indexOf("08 09"), rows.hexColumn(8));
    assertEquals(header.indexOf("0F  0123"), rows.hexColumn(15));
    assertEquals(header.indexOf("0123456789"), rows.textColumn(0));
    assertEquals(header.length(), rows.columns());
  }

  @Test
  void everyColumnHitsTheByteDrawnThere() {
    for (int width : HexRows.WIDTHS) {
      HexRows rows = new HexRows(1000, width);
      int hexHits = 0;
      int textHits = 0;
      for (int column = 0; column < rows.columns() + 3; column++) {
        int hex = rows.hexByteAt(column);
        if (hex >= 0) {
          hexHits++;
          int start = rows.hexColumn(hex);
          assertTrue(column == start || column == start + 1, width + " column " + column);
        }
        int text = rows.textByteAt(column);
        if (text >= 0) {
          textHits++;
          assertEquals(rows.textColumn(text), column);
        }
        assertFalse(hex >= 0 && text >= 0);
      }
      assertEquals(2 * width, hexHits);
      assertEquals(width, textHits);
    }
  }

  @Test
  void theOffsetColumnWidensPastFourGibibytes() {
    assertEquals(8, new HexRows(1L << 32, 16).offsetDigits());
    assertEquals("FFFFFFF0", new HexRows(1L << 32, 16).offsetText((1L << 32) - 16));
    HexRows huge = new HexRows(HexFixtures.HugeSource.SIZE, 32);
    assertEquals(16, huge.offsetDigits());
    assertEquals("000000FFFFFFFFE0", huge.offsetText(huge.rowStart(huge.rowCount() - 1)));
    assertEquals(16 + 2, huge.hexColumn(0));
  }

  @Test
  void theLastRowIsPartial() {
    HexRows rows = new HexRows(37, 16);
    assertEquals(3, rows.rowCount());
    assertEquals(16, rows.rowLength(1));
    assertEquals(5, rows.rowLength(2));
    assertEquals(0, rows.rowLength(3));
    assertEquals(2, rows.rowOf(36));
    assertEquals(0, new HexRows(0, 8).rowCount());
    assertEquals(4, new HexRows(32, 8).rowCount());
  }

  @Test
  void aRowBuildsFromCachedBlocksOnly() throws IOException {
    HexBlocks blocks = new HexBlocks(new HexFixtures.HugeSource());
    HexRows rows = new HexRows(HexFixtures.HugeSource.SIZE, 16);
    long last = rows.rowCount() - 1;
    assertNull(rows.row(last, blocks));
    blocks.load(rows.rowStart(last), 16, new CancellationToken());
    HexRow row = rows.row(last, blocks);
    assertEquals(HexFixtures.HugeSource.SIZE - 16, row.offset());
    assertEquals("000000FFFFFFFFF0", row.offsetText());
    assertEquals(16, row.length());
    for (int i = 0; i < 16; i++) {
      byte b = HexFixtures.HugeSource.at(row.offset() + i);
      assertEquals(HexRows.hexCell(b), row.hex(i));
      assertEquals(HexRows.textCell(b), row.text(i));
      assertFalse(row.replaced(i));
    }
  }

  @Test
  void onlyPrintableAsciiHasItsOwnTextCell() {
    assertEquals('.', HexRows.textCell((byte) 0x1f));
    assertEquals(' ', HexRows.textCell((byte) 0x20));
    assertEquals('~', HexRows.textCell((byte) 0x7e));
    assertEquals('.', HexRows.textCell((byte) 0x7f));
    assertEquals('.', HexRows.textCell((byte) 0x80));
    assertEquals('.', HexRows.textCell((byte) 0xe9));
    assertEquals("00", HexRows.hexCell((byte) 0));
    assertEquals("FF", HexRows.hexCell((byte) 0xff));
    assertEquals("4D", HexRows.hexCell((byte) 'M'));
  }

  @Test
  void aWidthOutsideTheChoicesIsRefused() {
    assertThrows(
        IllegalArgumentException.class,
        new Executable() {
          @Override
          public void execute() {
            new HexRows(10, 12);
          }
        });
  }
}
