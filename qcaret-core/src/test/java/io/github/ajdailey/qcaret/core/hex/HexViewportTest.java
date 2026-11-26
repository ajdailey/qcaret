package io.github.ajdailey.qcaret.core.hex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class HexViewportTest {
  @Test
  void aSmallSourceScrollsOneUnitPerRow() {
    HexViewport viewport = new HexViewport(16);
    viewport.setSize(1000);
    viewport.resize(10);
    assertEquals(63, viewport.rowCount());
    assertFalse(viewport.scaled());
    assertEquals(63, viewport.scrollbarMaximum());
    assertEquals(10, viewport.scrollbarThumb());
    viewport.setScrollbarSelection(20);
    assertEquals(20, viewport.topRow());
    assertEquals(20, viewport.scrollbarSelection());
    viewport.scrollBy(1000);
    assertEquals(53, viewport.topRow());
    assertEquals(10, viewport.visibleRows());
    viewport.resize(100);
    assertEquals(0, viewport.topRow());
    assertEquals(63, viewport.visibleRows());
  }

  @Test
  void aHugeSourceScalesTheScrollBar() {
    HexViewport viewport = new HexViewport(16);
    viewport.setSize(HexFixtures.HugeSource.SIZE);
    viewport.resize(60);
    long maxTop = (HexFixtures.HugeSource.SIZE >> 4) - 60;
    assertEquals(maxTop, viewport.maxTop());
    assertTrue(viewport.scaled());
    assertEquals(HexViewport.SCROLLBAR_RANGE + 1, viewport.scrollbarMaximum());
    assertEquals(1, viewport.scrollbarThumb());
    viewport.setScrollbarSelection(HexViewport.SCROLLBAR_RANGE);
    assertEquals(maxTop, viewport.topRow());
    assertEquals(HexViewport.SCROLLBAR_RANGE, viewport.scrollbarSelection());
    viewport.setScrollbarSelection(HexViewport.SCROLLBAR_RANGE / 2);
    assertEquals(Math.round(maxTop / 2.0), viewport.topRow());
    viewport.setScrollbarSelection(0);
    assertEquals(0, viewport.topRow());
    viewport.scrollBy(Long.MAX_VALUE);
    assertEquals(maxTop, viewport.topRow());
    viewport.ensureOffsetVisible(5L << 32);
    assertEquals(5L << 28, viewport.topRow());
  }

  @Test
  void aWidthChangeKeepsTheTopByteInTheTopRow() {
    HexViewport viewport = new HexViewport(16);
    viewport.setSize(100_000);
    viewport.resize(10);
    viewport.scrollToRow(101);
    assertEquals(1616, viewport.topOffset());
    viewport.setBytesPerRow(32);
    assertEquals(50, viewport.topRow());
    viewport.setBytesPerRow(8);
    assertEquals(200, viewport.topRow());
    assertEquals(12500, viewport.rowCount());
  }

  @Test
  void ensureVisibleScrollsTheLeastDistance() {
    HexViewport viewport = new HexViewport(8);
    viewport.setSize(800);
    viewport.resize(10);
    viewport.ensureVisible(15);
    assertEquals(6, viewport.topRow());
    viewport.ensureVisible(10);
    assertEquals(6, viewport.topRow());
    viewport.ensureVisible(2);
    assertEquals(2, viewport.topRow());
    viewport.setSize(40);
    assertEquals(0, viewport.topRow());
    assertEquals(5, viewport.visibleRows());
  }
}
