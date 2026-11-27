package io.github.ajdailey.qcaret.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.ajdailey.qcaret.app.view.HexSelection;
import io.github.ajdailey.qcaret.core.hex.HexRows;
import org.junit.jupiter.api.Test;

/** Go to Offset parsing, the status line and Copy as Hex text. */
class HexOffsetTextTest {
  @Test
  void offsetsParseAsDecimalHexOrRelative() {
    assertEquals(46, HexOffsetText.parse(" 46 ", 0, 100));
    assertEquals(46, HexOffsetText.parse("0x2E", 0, 100));
    assertEquals(46, HexOffsetText.parse("0X2e", 0, 100));
    assertEquals(60, HexOffsetText.parse("+10", 50, 100));
    assertEquals(34, HexOffsetText.parse("- 0x10", 50, 100));
    assertEquals(100, HexOffsetText.parse("100", 0, 100));
  }

  @Test
  void badTextAndOffsetsOutsideTheSourceAreRefused() {
    for (String text : new String[] {"", "x", "0x", "1.5", "12a", "--1", "101", "-51", "0x1g"}) {
      assertEquals(-1, HexOffsetText.parse(text, 50, 100), text);
    }
    assertEquals(-1, HexOffsetText.parse("99999999999999999999", 0, Long.MAX_VALUE));
  }

  @Test
  void theStatusShowsTheCaretTheSelectionAndTheSize() {
    HexRows rows = new HexRows(15_900_672, 16);
    assertEquals(
        "Offset 0x0000002E (46)   Size 0x00F2A000 (15,900,672)",
        HexOffsetText.status(rows, new HexSelection(46, 46, false)));
    assertEquals(
        "Offset 0x0000002E (46)   Selection 2 bytes   Size 0x00F2A000 (15,900,672)",
        HexOffsetText.status(rows, new HexSelection(44, 46, false)));
    HexRows large = new HexRows(1L << 40, 16);
    assertEquals(
        "Offset 0x0000000000000001 (1)   Size 0x0000010000000000 (1,099,511,627,776)",
        HexOffsetText.status(large, new HexSelection(1, 1, true)));
  }

  @Test
  void hexPairsAreSpaceSeparated() {
    byte[] bytes = {0x4d, 0x5a, (byte) 0x90, 0x00, 0x7f};
    assertEquals("4D 5A 90 00", HexOffsetText.hexPairs(bytes, 4));
    assertEquals("", HexOffsetText.hexPairs(bytes, 0));
  }
}
