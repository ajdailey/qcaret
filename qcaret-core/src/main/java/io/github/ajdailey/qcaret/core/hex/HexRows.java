package io.github.ajdailey.qcaret.core.hex;

import java.util.BitSet;
import java.util.List;

/**
 * The row layout of a hex view over a source of a given size: row ranges, the offset text and the
 * column of each cell, in character cells. A row is the offset column, two spaces, the hex column
 * (two digits per byte, one space between bytes, one more after each group of {@value #GROUP}), two
 * spaces and the text column (one cell per byte). Immutable.
 */
public final class HexRows {
  /** The bytes-per-row choices. */
  public static final List<Integer> WIDTHS = List.of(8, 16, 32);

  /** Bytes per group in the hex column. */
  public static final int GROUP = 8;

  private static final long FOUR_GIB = 1L << 32;
  private static final char[] DIGITS = "0123456789ABCDEF".toCharArray();

  private final long size;
  private final int bytesPerRow;
  private final int offsetDigits;
  private final int offsetColumn;

  /**
   * Creates the layout with an offset column as wide as its digits.
   *
   * @param size the source's byte count
   * @param bytesPerRow one of {@link #WIDTHS}
   * @throws IllegalArgumentException when the width is not one of {@link #WIDTHS} or the size is
   *     negative
   */
  public HexRows(long size, int bytesPerRow) {
    this(size, bytesPerRow, 0);
  }

  /**
   * Creates the layout.
   *
   * @param size the source's byte count
   * @param bytesPerRow one of {@link #WIDTHS}
   * @param minOffsetColumn the fewest cells of the offset column, such as its heading's length
   * @throws IllegalArgumentException when the width is not one of {@link #WIDTHS} or the size is
   *     negative
   */
  public HexRows(long size, int bytesPerRow, int minOffsetColumn) {
    if (size < 0) {
      throw new IllegalArgumentException("size " + size);
    }
    this.size = size;
    this.bytesPerRow = checkWidth(bytesPerRow);
    this.offsetDigits = size > FOUR_GIB ? 16 : 8;
    this.offsetColumn = Math.max(offsetDigits, minOffsetColumn);
  }

  /**
   * Checks a bytes-per-row value.
   *
   * @param bytesPerRow the value
   * @return the value
   * @throws IllegalArgumentException when it is not one of {@link #WIDTHS}
   */
  public static int checkWidth(int bytesPerRow) {
    if (!WIDTHS.contains(bytesPerRow)) {
      throw new IllegalArgumentException("bytes per row " + bytesPerRow);
    }
    return bytesPerRow;
  }

  /**
   * Returns the source's byte count.
   *
   * @return the size
   */
  public long size() {
    return size;
  }

  /**
   * Returns the bytes per row.
   *
   * @return one of {@link #WIDTHS}
   */
  public int bytesPerRow() {
    return bytesPerRow;
  }

  /**
   * Returns the row count; the last row may be partial.
   *
   * @return 0 for an empty source
   */
  public long rowCount() {
    return rowCount(size, bytesPerRow);
  }

  /**
   * Returns the row count for a size and width.
   *
   * @param size the byte count
   * @param bytesPerRow bytes per row
   * @return {@code ceil(size / bytesPerRow)}
   */
  public static long rowCount(long size, int bytesPerRow) {
    return size / bytesPerRow + (size % bytesPerRow == 0 ? 0 : 1);
  }

  /**
   * Returns the row holding a byte.
   *
   * @param offset a byte offset
   * @return the row
   */
  public long rowOf(long offset) {
    return offset / bytesPerRow;
  }

  /**
   * Returns a row's first byte.
   *
   * @param row a row
   * @return the offset
   */
  public long rowStart(long row) {
    return row * bytesPerRow;
  }

  /**
   * Returns the bytes in a row.
   *
   * @param row a row
   * @return {@link #bytesPerRow}, fewer on the last row, 0 past the end
   */
  public int rowLength(long row) {
    long start = rowStart(row);
    return (int) Math.max(0, Math.min(bytesPerRow, size - start));
  }

  /**
   * Returns the digits of the offset column.
   *
   * @return 8, or 16 when the source is larger than 4 GiB
   */
  public int offsetDigits() {
    return offsetDigits;
  }

  /**
   * Returns the cells of the offset column: its digits, or more for a longer heading.
   *
   * @return the cells
   */
  public int offsetColumn() {
    return offsetColumn;
  }

  /**
   * Formats an offset for the offset column.
   *
   * @param offset a byte offset
   * @return {@link #offsetDigits} uppercase hex digits
   */
  public String offsetText(long offset) {
    char[] text = new char[offsetDigits];
    long value = offset;
    for (int i = offsetDigits - 1; i >= 0; i--) {
      text[i] = DIGITS[(int) (value & 0xf)];
      value >>>= 4;
    }
    return new String(text);
  }

  /**
   * Returns the column of a byte's first hex digit.
   *
   * @param index the byte's index in its row
   * @return the column
   */
  public int hexColumn(int index) {
    return offsetColumn + 2 + index * 3 + index / GROUP;
  }

  /**
   * Returns the column of a byte's text cell.
   *
   * @param index the byte's index in its row
   * @return the column
   */
  public int textColumn(int index) {
    return hexColumn(bytesPerRow - 1) + 4 + index;
  }

  /**
   * Returns the width of a full row.
   *
   * @return the columns
   */
  public int columns() {
    return textColumn(bytesPerRow);
  }

  /**
   * Returns the byte whose hex digits cover a column.
   *
   * @param column a column
   * @return the byte's index in the row, or -1 outside the hex digits
   */
  public int hexByteAt(int column) {
    int index = column - offsetColumn - 2;
    if (index < 0) {
      return -1;
    }
    int group = index / (GROUP * 3 + 1);
    int inGroup = index % (GROUP * 3 + 1);
    int byteIndex = group * GROUP + inGroup / 3;
    return inGroup % 3 == 2 || inGroup == GROUP * 3 || byteIndex >= bytesPerRow ? -1 : byteIndex;
  }

  /**
   * Returns the byte whose text cell is a column.
   *
   * @param column a column
   * @return the byte's index in the row, or -1 outside the text column
   */
  public int textByteAt(int column) {
    int index = column - textColumn(0);
    return index >= 0 && index < bytesPerRow ? index : -1;
  }

  /**
   * Builds a row from the cache, without reading the source.
   *
   * @param row a row below {@link #rowCount}
   * @param blocks the source's cache
   * @return the row, or null when a block of it is not cached
   */
  public HexRow row(long row, HexBlocks blocks) {
    int length = rowLength(row);
    byte[] bytes = new byte[length];
    BitSet replaced = new BitSet(length);
    if (!blocks.copy(rowStart(row), bytes, length, replaced)) {
      return null;
    }
    return new HexRow(rowStart(row), offsetText(rowStart(row)), bytes, replaced);
  }

  /**
   * Reports whether a byte has its own glyph in the text column.
   *
   * @param b the byte
   * @return true for ASCII 0x20 to 0x7E
   */
  public static boolean printable(byte b) {
    return b >= 0x20 && b <= 0x7e;
  }

  /**
   * Returns a byte's text cell.
   *
   * @param b the byte
   * @return the ASCII char, or {@code '.'} when not {@link #printable}
   */
  public static char textCell(byte b) {
    return printable(b) ? (char) b : '.';
  }

  /**
   * Returns a byte's hex cell.
   *
   * @param b the byte
   * @return two uppercase hex digits
   */
  public static String hexCell(byte b) {
    return new String(new char[] {DIGITS[(b >> 4) & 0xf], DIGITS[b & 0xf]});
  }
}
