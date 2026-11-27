package io.github.ajdailey.qcaret.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.view.HexKey;
import io.github.ajdailey.qcaret.app.view.HexSelection;
import org.junit.jupiter.api.Test;

/** The hex caret's moves, clicks, drags and clamping over a 40-byte source of 16-byte rows. */
class HexCursorTest {
  private static HexCursor cursor() {
    HexCursor cursor = new HexCursor(16);
    cursor.setSize(40);
    return cursor;
  }

  private static void assertSelection(long anchor, long caret, HexCursor cursor) {
    HexSelection s = cursor.selection();
    assertEquals(anchor + ".." + caret, s.anchor() + ".." + s.caret());
  }

  @Test
  void keysMoveByByteRowPageAndEnd() {
    HexCursor c = cursor();
    c.move(HexKey.RIGHT, false, 2);
    assertSelection(1, 1, c);
    c.move(HexKey.DOWN, false, 2);
    assertSelection(17, 17, c);
    c.move(HexKey.ROW_END, false, 2);
    assertSelection(31, 31, c);
    c.move(HexKey.ROW_START, false, 2);
    assertSelection(16, 16, c);
    c.move(HexKey.UP, false, 2);
    c.move(HexKey.UP, false, 2);
    assertSelection(0, 0, c);
    c.move(HexKey.PAGE_DOWN, false, 2);
    assertSelection(32, 32, c);
    c.move(HexKey.DOWN, false, 2);
    assertSelection(40, 40, c);
    c.move(HexKey.PAGE_UP, false, 3);
    assertSelection(0, 0, c);
    c.move(HexKey.LEFT, false, 2);
    assertSelection(0, 0, c);
    c.move(HexKey.SOURCE_END, false, 2);
    assertSelection(40, 40, c);
    c.move(HexKey.RIGHT, false, 2);
    assertSelection(40, 40, c);
  }

  @Test
  void shiftExtendsAndAPlainMoveCollapses() {
    HexCursor c = cursor();
    c.move(HexKey.RIGHT, true, 2);
    c.move(HexKey.RIGHT, true, 2);
    assertSelection(0, 2, c);
    assertEquals(2, c.selection().length());
    c.move(HexKey.ROW_END, true, 2);
    assertSelection(0, 16, c);
    c.move(HexKey.LEFT, false, 2);
    assertSelection(15, 15, c);
  }

  @Test
  void aDragSelectsBothEndBytesInEitherDirection() {
    HexCursor c = cursor();
    c.click(5, true, false);
    assertTrue(c.selection().textColumn());
    c.drag(9);
    assertSelection(5, 10, c);
    c.drag(2);
    assertSelection(6, 2, c);
    assertEquals(2, c.selection().start());
    assertEquals(6, c.selection().end());
    c.click(20, false, true);
    assertSelection(6, 21, c);
    c.drag(30);
    assertSelection(6, 21, c);
  }

  @Test
  void aSmallerSourceClampsAndSelectAllCoversIt() {
    HexCursor c = cursor();
    c.select(10, 30);
    c.setSize(12);
    assertSelection(10, 12, c);
    c.selectAll();
    assertSelection(0, 12, c);
    c.switchColumn();
    assertTrue(c.selection().textColumn());
    c.switchColumn();
    assertFalse(c.selection().textColumn());
  }
}
