package io.github.ajdailey.qcaret.core.hex;

import io.github.ajdailey.qcaret.core.buffer.TextSnapshot;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.WorkerThread;
import io.github.ajdailey.qcaret.core.io.Encoders;
import io.github.ajdailey.qcaret.core.text.TextEncoding;
import java.nio.charset.CharsetEncoder;
import java.util.Arrays;

/**
 * Maps char offsets of one text version to byte offsets in its encoding, BOM first: the bytes Save
 * writes. Immutable and safe to read from any thread.
 *
 * <p>The text is split into blocks of {@value #BLOCK_CHARS} chars, a block holding one char more
 * where its end would split a surrogate pair. A charset of one byte per char, and UTF-16, map
 * arithmetically and hold no index ({@link #indexed} false); every other charset holds each block's
 * starting byte offset, 8 bytes per block. A lookup encodes at most one block.
 *
 * <p>A char the charset cannot encode, or a lone surrogate, maps to the encoder's replacement
 * bytes; in an arithmetic charset each char of an unencodable surrogate pair gets its own
 * replacement, so the mapping stays one width per char.
 */
public final class ByteOffsets {
  /** Chars per block. */
  public static final int BLOCK_CHARS = 1 << 16;

  private final TextSnapshot text;
  private final TextEncoding encoding;
  private final int bomLength;
  private final int width;
  private final int blocks;
  private final long[] starts;

  private ByteOffsets(TextSnapshot text, TextEncoding encoding, int width, long[] starts) {
    this.text = text;
    this.encoding = encoding;
    this.bomLength = encoding.bomBytes().length;
    this.width = width;
    this.blocks = blockCount(text);
    this.starts = starts;
  }

  /**
   * Reports whether an encoding needs a block index, which {@link #build} computes by encoding the
   * whole text: run such a build on a worker.
   *
   * @param encoding the encoding
   * @return false for a charset of one byte per char and for UTF-16
   * @throws UnsupportedOperationException when the charset has no encoder
   */
  public static boolean indexed(TextEncoding encoding) {
    return width(encoding) == 0;
  }

  /**
   * Builds the offsets of a text. Without an index this returns at once; with one it encodes every
   * block, checking the token between blocks.
   *
   * @param text the text version
   * @param encoding the encoding Save writes
   * @param token canceled to stop the build
   * @return the offsets
   * @throws java.util.concurrent.CancellationException when canceled
   * @throws UnsupportedOperationException when the charset has no encoder
   */
  @WorkerThread
  public static ByteOffsets build(
      TextSnapshot text, TextEncoding encoding, CancellationToken token) {
    int width = width(encoding);
    if (width > 0) {
      return new ByteOffsets(text, encoding, width, null);
    }
    long[] starts = new long[blockCount(text) + 1];
    fill(text, encoding, starts, 0, token);
    return new ByteOffsets(text, encoding, 0, starts);
  }

  /**
   * Builds the offsets of a later version of this text in the same encoding, reusing the index up
   * to the block before the one holding {@code firstChanged}.
   *
   * @param newText the later version
   * @param firstChanged the first offset at which the two versions differ
   * @param token canceled to stop the build
   * @return the offsets of {@code newText}
   * @throws java.util.concurrent.CancellationException when canceled
   */
  @WorkerThread
  public ByteOffsets update(TextSnapshot newText, int firstChanged, CancellationToken token) {
    if (width > 0) {
      return new ByteOffsets(newText, encoding, width, null);
    }
    int newBlocks = blockCount(newText);
    int changed = Math.max(0, Math.min(firstChanged, Math.min(text.length(), newText.length())));
    int keep = changed == 0 ? 0 : Math.min((changed - 1) / BLOCK_CHARS, newBlocks);
    keep = Math.min(keep, blocks);
    long[] newStarts = new long[newBlocks + 1];
    System.arraycopy(starts, 0, newStarts, 0, keep + 1);
    fill(newText, encoding, newStarts, keep, token);
    return new ByteOffsets(newText, encoding, 0, newStarts);
  }

  /**
   * Returns the text version these offsets map.
   *
   * @return the text
   */
  public TextSnapshot text() {
    return text;
  }

  /**
   * Returns the encoding.
   *
   * @return the encoding
   */
  public TextEncoding encoding() {
    return encoding;
  }

  /**
   * Returns the encoded length, BOM included.
   *
   * @return the byte count
   */
  public long size() {
    return bomLength + (width > 0 ? (long) text.length() * width : starts[blocks]);
  }

  /**
   * Returns the byte offset of a char's first byte. An offset between the halves of a surrogate
   * pair maps as the pair's start.
   *
   * @param charOffset a char offset from 0 to the text length; clamped to that range
   * @return the byte offset, BOM included; {@link #size} for the text length
   */
  public long byteOffset(int charOffset) {
    int length = text.length();
    int c = Math.max(0, Math.min(charOffset, length));
    if (c == length) {
      return size();
    }
    if (SliceEncoder.splitsPair(text, c)) {
      c--;
    }
    if (width > 0) {
      return bomLength + (long) c * width;
    }
    int block = blockOfChar(c);
    return bomLength + starts[block] + encoder().encode(text, blockStart(block), c, null);
  }

  /**
   * Returns the char whose bytes hold a byte. A BOM byte maps to 0; any byte of a surrogate pair
   * maps to the pair's start.
   *
   * @param byteOffset a byte offset; clamped to 0
   * @return the char offset; the text length for an offset at or past {@link #size}
   */
  public int charOffset(long byteOffset) {
    if (byteOffset < bomLength) {
      return 0;
    }
    long position = byteOffset - bomLength;
    if (position >= size() - bomLength) {
      return text.length();
    }
    if (width > 0) {
      int c = (int) (position / width);
      return SliceEncoder.splitsPair(text, c) ? c - 1 : c;
    }
    int block = blockOfByte(position);
    return walk(blockStart(block), position - starts[block]);
  }

  /** Returns the char holding byte {@code remaining} of the bytes from char {@code at} on. */
  private int walk(int at, long remaining) {
    SliceEncoder encoder = encoder();
    int end = blockEnd(blockOfChar(at));
    int step = 256;
    while (at < end) {
      int stop = Math.min(end, at + step);
      if (SliceEncoder.splitsPair(text, stop)) {
        stop++;
      }
      long bytes = encoder.encode(text, at, stop, null);
      if (bytes <= remaining) {
        remaining -= bytes;
        at = stop;
      } else if (step > 1) {
        step = 1;
      } else {
        return at;
      }
    }
    return Math.min(at, text.length());
  }

  /** Returns the number of blocks. */
  int blockCount() {
    return blocks;
  }

  /** Returns the first char of a block. */
  int blockStart(int block) {
    return blockStart(text, block);
  }

  /** Returns the offset after a block's last char. */
  int blockEnd(int block) {
    return block + 1 < blocks ? blockStart(block + 1) : text.length();
  }

  /** Returns the byte offset of a block's first byte, after the BOM. */
  long blockByteStart(int block) {
    return width > 0 ? (long) blockStart(block) * width : starts[block];
  }

  /** Returns the block holding a char below the text length. */
  int blockOfChar(int charOffset) {
    int block = charOffset / BLOCK_CHARS;
    if (block > 0 && charOffset < blockStart(block)) {
      block--;
    }
    return block;
  }

  /** Returns the block holding a byte below {@code size() - bomLength}, the BOM excluded. */
  int blockOfByte(long position) {
    if (width > 0) {
      return blockOfChar((int) (position / width));
    }
    int found = Arrays.binarySearch(starts, 0, blocks, position);
    return found >= 0 ? found : -found - 2;
  }

  /** Returns the BOM's byte count. */
  int bomLength() {
    return bomLength;
  }

  /** Returns a new encoder for this encoding, for one thread. */
  SliceEncoder encoder() {
    return new SliceEncoder(encoding.charset(), width > 0);
  }

  private static void fill(
      TextSnapshot text, TextEncoding encoding, long[] starts, int from, CancellationToken token) {
    SliceEncoder encoder = new SliceEncoder(encoding.charset(), false);
    int count = starts.length - 1;
    for (int block = from; block < count; block++) {
      token.check();
      int end = block + 1 < count ? blockStart(text, block + 1) : text.length();
      starts[block + 1] = starts[block] + encoder.encode(text, blockStart(text, block), end, null);
    }
  }

  private static int blockStart(CharSequence text, int block) {
    if (block == 0) {
      return 0;
    }
    int start = (int) Math.min(text.length(), (long) block * BLOCK_CHARS);
    return SliceEncoder.splitsPair(text, start) ? start + 1 : start;
  }

  private static int blockCount(CharSequence text) {
    int length = text.length();
    int count = (int) (((long) length + BLOCK_CHARS - 1) / BLOCK_CHARS);
    if (count > 1 && blockStart(text, count - 1) >= length) {
      count--;
    }
    return count;
  }

  /** Returns the bytes per char of an arithmetic encoding, or 0 when it needs an index. */
  private static int width(TextEncoding encoding) {
    CharsetEncoder encoder = Encoders.reporting(encoding.charset());
    int width = encoding.unitWidth() == 2 ? 2 : encoder.maxBytesPerChar() == 1.0f ? 1 : 0;
    return width > 0 && encoder.replacement().length == width ? width : 0;
  }
}
