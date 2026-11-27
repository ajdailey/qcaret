package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.view.HexKey;
import io.github.ajdailey.qcaret.app.view.HexSelection;

/**
 * A hex view's caret and selection over a source of a given size ({@link HexSelection}): positions
 * from 0 to the size. A click puts the caret on a byte; a drag or Shift+click selects from the
 * first byte to the last, both included. Confined to the UI thread.
 */
final class HexCursor {
  private long size;
  private int bytesPerRow;
  private long anchor;
  private long caret;
  private boolean textColumn;
  private long pressed = -1;

  HexCursor(int bytesPerRow) {
    this.bytesPerRow = bytesPerRow;
  }

  HexSelection selection() {
    return new HexSelection(anchor, caret, textColumn);
  }

  /** Sets the source size, clamping the caret and the anchor to it. */
  void setSize(long value) {
    size = Math.max(0, value);
    anchor = clamp(anchor);
    caret = clamp(caret);
  }

  void setBytesPerRow(int value) {
    bytesPerRow = value;
  }

  /**
   * Moves the caret.
   *
   * @param key the move
   * @param extend true to keep the anchor
   * @param pageRows rows a page moves
   */
  void move(HexKey key, boolean extend, int pageRows) {
    long page = (long) Math.max(1, pageRows) * bytesPerRow;
    long rowStart = caret - caret % bytesPerRow;
    long target =
        switch (key) {
          case LEFT -> caret - 1;
          case RIGHT -> caret + 1;
          case UP -> caret >= bytesPerRow ? caret - bytesPerRow : caret;
          case DOWN -> Math.min(size, caret + bytesPerRow);
          case PAGE_UP -> caret - page;
          case PAGE_DOWN -> caret + page;
          case ROW_START -> rowStart;
          case ROW_END -> rowStart + bytesPerRow - (extend ? 0 : 1);
          case SOURCE_START -> 0;
          case SOURCE_END -> size;
        };
    caret = clamp(target);
    if (!extend) {
      anchor = caret;
    }
  }

  /**
   * Handles a click on a byte.
   *
   * @param offset the byte; the size for the cell after the last byte
   * @param text true in the text column
   * @param extend true to select from the anchor to the byte, the byte included
   */
  void click(long offset, boolean text, boolean extend) {
    textColumn = text;
    long at = clamp(offset);
    if (extend) {
      caret = at >= anchor ? clamp(at + 1) : at;
      pressed = -1;
    } else {
      anchor = at;
      caret = at;
      pressed = at;
    }
  }

  /**
   * Selects from the byte the last click pressed to a byte, both included.
   *
   * @param offset the byte under the pointer
   */
  void drag(long offset) {
    if (pressed < 0) {
      return;
    }
    long at = clamp(offset);
    if (at >= pressed) {
      anchor = pressed;
      caret = clamp(at + 1);
    } else {
      anchor = clamp(pressed + 1);
      caret = at;
    }
  }

  /** Selects a range; the caret ends at {@code to}. */
  void select(long from, long to) {
    anchor = clamp(from);
    caret = clamp(to);
  }

  void selectAll() {
    select(0, size);
  }

  void switchColumn() {
    textColumn = !textColumn;
  }

  private long clamp(long position) {
    return Math.max(0, Math.min(position, size));
  }
}
