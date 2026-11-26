package io.github.ajdailey.qcaret.core.hex;

import io.github.ajdailey.qcaret.core.concurrent.WorkerThread;
import java.nio.charset.Charset;
import java.util.BitSet;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A text version's bytes as Save writes them: the BOM, then the text in its charset. A read encodes
 * only the {@link ByteOffsets} blocks it touches and keeps the last one. A char the charset cannot
 * encode reads as the encoder's replacement bytes, marked in the {@code replaced} set.
 */
public final class EncodedHexSource implements HexSource {
  private final ByteOffsets offsets;
  private final byte[] bom;
  private final ReentrantLock lock = new ReentrantLock();
  private final SliceEncoder.Collector block = new SliceEncoder.Collector();
  private SliceEncoder encoder;
  private int cached = -1;

  /**
   * Creates the source.
   *
   * @param offsets the offsets of the text version to show
   */
  public EncodedHexSource(ByteOffsets offsets) {
    this.offsets = offsets;
    this.bom = offsets.encoding().bomBytes();
  }

  /**
   * Returns the offsets this source reads through, for mapping bytes to chars.
   *
   * @return the offsets
   */
  public ByteOffsets offsets() {
    return offsets;
  }

  @Override
  public long size() {
    return offsets.size();
  }

  @Override
  public Charset charset() {
    return offsets.encoding().charset();
  }

  @Override
  @WorkerThread
  public void read(long position, byte[] dst, int length, BitSet replaced) {
    Objects.checkFromIndexSize(position, length, size());
    Objects.checkFromIndexSize(0, length, dst.length);
    if (replaced != null) {
      replaced.clear(0, length);
    }
    lock.lock();
    try {
      copy(position, dst, length, replaced);
    } finally {
      lock.unlock();
    }
  }

  private void copy(long position, byte[] dst, int length, BitSet replaced) {
    int done = 0;
    long at = position;
    while (done < length && at < bom.length) {
      dst[done++] = bom[(int) at++];
    }
    while (done < length) {
      long inText = at - bom.length;
      int index = offsets.blockOfByte(inText);
      encode(index);
      int from = (int) (inText - offsets.blockByteStart(index));
      int count = Math.min(length - done, block.length() - from);
      System.arraycopy(block.bytes(), from, dst, done, count);
      if (replaced != null) {
        BitSet marks = block.replaced();
        for (int i = marks.nextSetBit(from);
            i >= 0 && i < from + count;
            i = marks.nextSetBit(i + 1)) {
          replaced.set(done + i - from);
        }
      }
      done += count;
      at += count;
    }
  }

  private void encode(int index) {
    if (index == cached) {
      return;
    }
    if (encoder == null) {
      encoder = offsets.encoder();
    }
    cached = -1;
    block.reset();
    encoder.encode(offsets.text(), offsets.blockStart(index), offsets.blockEnd(index), block);
    cached = index;
  }
}
