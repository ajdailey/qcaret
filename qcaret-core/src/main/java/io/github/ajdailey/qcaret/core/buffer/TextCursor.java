package io.github.ajdailey.qcaret.core.buffer;

import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * Sequential char reads over one text version, forward or backward, for one thread. The cursor
 * copies one leaf at a time into its own buffer, so a step inside a leaf is an array read and a
 * step across leaves is one tree descent.
 */
public final class TextCursor {
  private final LeafText text;
  private final int length;
  private final int[] at = new int[1];
  private char[] chars = new char[RopeStorage.LEAF_MAX];
  private int base;
  private int count;
  private int pos;

  TextCursor(LeafText text, int offset) {
    this.text = text;
    this.length = text.length();
    seek(offset);
  }

  /**
   * Returns the offset of the char {@link #next} returns.
   *
   * @return an offset from 0 to the text length
   */
  public int position() {
    return base + pos;
  }

  /**
   * Moves the cursor.
   *
   * @param offset an offset from 0 to the text length
   * @throws IndexOutOfBoundsException when the offset is out of range
   */
  public void seek(int offset) {
    Objects.checkIndex(offset, length + 1);
    if (offset >= base && offset <= base + count) {
      pos = offset - base;
    } else if (offset == length) {
      base = length;
      count = 0;
      pos = 0;
    } else {
      load(offset);
    }
  }

  /**
   * Reports whether a char follows the position.
   *
   * @return true when {@link #position} is below the text length
   */
  public boolean hasNext() {
    return base + pos < length;
  }

  /**
   * Returns the char at the position and moves past it.
   *
   * @return the char
   * @throws NoSuchElementException at the end of the text
   */
  public char next() {
    if (pos == count) {
      if (base + count >= length) {
        throw new NoSuchElementException();
      }
      load(base + count);
    }
    return chars[pos++];
  }

  /**
   * Reports whether a char precedes the position.
   *
   * @return true when {@link #position} is above 0
   */
  public boolean hasPrevious() {
    return base + pos > 0;
  }

  /**
   * Moves back one char and returns it.
   *
   * @return the char before the position
   * @throws NoSuchElementException at offset 0
   */
  public char previous() {
    if (pos == 0) {
      if (base == 0) {
        throw new NoSuchElementException();
      }
      load(base - 1);
      pos++;
    }
    return chars[--pos];
  }

  private void load(int index) {
    String leaf = text.leaf(index, at);
    count = leaf.length();
    if (chars.length < count) {
      chars = new char[count];
    }
    leaf.getChars(0, count, chars, 0);
    base = at[0];
    pos = index - base;
  }
}
