package io.github.ajdailey.qcaret.core.hex;

import java.util.BitSet;

/**
 * One row of a hex view, built by {@link HexRows#row}. The caller does not modify the arrays.
 *
 * @param offset the row's first byte
 * @param offsetText the offset column's text
 * @param bytes the row's bytes, fewer than the row width on the last row
 * @param replaced bit {@code i} set when {@code bytes[i]} is part of a replacement for a char the
 *     charset cannot encode, which the view draws in the error color
 */
public record HexRow(long offset, String offsetText, byte[] bytes, BitSet replaced) {
  /**
   * Returns the byte count.
   *
   * @return the length of {@link #bytes}
   */
  public int length() {
    return bytes.length;
  }

  /**
   * Returns a byte's hex cell.
   *
   * @param index the byte's index in the row
   * @return two uppercase hex digits
   */
  public String hex(int index) {
    return HexRows.hexCell(bytes[index]);
  }

  /**
   * Returns a byte's text cell.
   *
   * @param index the byte's index in the row
   * @return the ASCII char, or {@code '.'}
   */
  public char text(int index) {
    return HexRows.textCell(bytes[index]);
  }

  /**
   * Reports whether a byte has its own glyph in the text column.
   *
   * @param index the byte's index in the row
   * @return false where the text column shows {@code '.'} for the byte
   */
  public boolean printable(int index) {
    return HexRows.printable(bytes[index]);
  }

  /**
   * Reports whether a byte is part of a replacement.
   *
   * @param index the byte's index in the row
   * @return true when the view draws it in the error color
   */
  public boolean replaced(int index) {
    return replaced.get(index);
  }
}
