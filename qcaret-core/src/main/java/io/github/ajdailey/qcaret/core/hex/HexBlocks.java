package io.github.ajdailey.qcaret.core.hex;

import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.WorkerThread;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.BitSet;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * An LRU cache of one {@link HexSource}'s bytes in blocks of {@value #BLOCK_BYTES} bytes, at most
 * {@value #CAPACITY} blocks. A worker fills it with {@link #load}; the UI thread reads it with
 * {@link #copy}, which never blocks on I/O. An evicted block's arrays serve the next load, and a
 * cached block's arrays are never written, so a copy never reads a block being reloaded. Safe to
 * use from several threads.
 */
public final class HexBlocks {
  /** Bytes per block. */
  public static final int BLOCK_BYTES = 1 << 16;

  /** Blocks kept. */
  public static final int CAPACITY = 16;

  private final HexSource source;
  private final ReentrantLock lock = new ReentrantLock();
  private final Map<Long, Block> blocks = new LinkedHashMap<Long, Block>(32, 0.75f, true);
  private final Deque<Block> free = new ArrayDeque<Block>();
  private long generation;

  /** One block's bytes and replacement marks. */
  private static final class Block {
    final byte[] bytes = new byte[BLOCK_BYTES];
    final BitSet replaced = new BitSet(BLOCK_BYTES);
    long index;
    int length;
  }

  /**
   * Creates an empty cache.
   *
   * @param source the bytes to cache
   */
  public HexBlocks(HexSource source) {
    this.source = source;
  }

  /**
   * Returns the source.
   *
   * @return the source
   */
  public HexSource source() {
    return source;
  }

  /**
   * Reports whether every byte of a range is cached.
   *
   * @param position the first byte
   * @param length the byte count
   * @return true when {@link #copy} of the range would succeed
   */
  public boolean cached(long position, int length) {
    lock.lock();
    try {
      for (long index = first(position); index <= last(position, length); index++) {
        if (!blocks.containsKey(index)) {
          return false;
        }
      }
      return true;
    } finally {
      lock.unlock();
    }
  }

  /**
   * Copies a range from the cache, or nothing when a block of it is missing. Never reads the
   * source.
   *
   * @param position the first byte
   * @param dst receives the bytes in {@code [0, length)}
   * @param length the byte count; the range lies inside the source
   * @param replaced when not null, receives the replacement marks as {@link HexSource#read} gives
   *     them
   * @return false when a block is not cached; {@code dst} is then unchanged
   * @throws IndexOutOfBoundsException when the range is outside the source or {@code dst}
   */
  public boolean copy(long position, byte[] dst, int length, BitSet replaced) {
    Objects.checkFromIndexSize(position, length, source.size());
    Objects.checkFromIndexSize(0, length, dst.length);
    lock.lock();
    try {
      if (!cached(position, length)) {
        return false;
      }
      if (replaced != null) {
        replaced.clear(0, length);
      }
      int done = 0;
      while (done < length) {
        long at = position + done;
        Block block = blocks.get(at / BLOCK_BYTES);
        int from = (int) (at % BLOCK_BYTES);
        int count = Math.min(length - done, block.length - from);
        System.arraycopy(block.bytes, from, dst, done, count);
        if (replaced != null) {
          for (int i = block.replaced.nextSetBit(from);
              i >= 0 && i < from + count;
              i = block.replaced.nextSetBit(i + 1)) {
            replaced.set(done + i - from);
          }
        }
        done += count;
      }
      return true;
    } finally {
      lock.unlock();
    }
  }

  /**
   * Reads the missing blocks of a range from the source, checking the token before each. Blocks on
   * I/O: call it on a worker. A block read before a {@link #clear} is dropped.
   *
   * @param position the first byte
   * @param length the byte count; the range lies inside the source
   * @param token canceled to stop between blocks
   * @throws HexSourceChangedException when the file changed; the cache is cleared
   * @throws IOException when a read fails
   * @throws java.util.concurrent.CancellationException when canceled
   */
  @WorkerThread
  public void load(long position, int length, CancellationToken token) throws IOException {
    Objects.checkFromIndexSize(position, length, source.size());
    for (long index = first(position); index <= last(position, length); index++) {
      token.check();
      long started;
      Block block;
      lock.lock();
      try {
        if (blocks.containsKey(index)) {
          continue;
        }
        started = generation;
        block = free.isEmpty() ? new Block() : free.pop();
      } finally {
        lock.unlock();
      }
      long start = index * BLOCK_BYTES;
      block.index = index;
      block.length = (int) Math.min(BLOCK_BYTES, source.size() - start);
      boolean read = false;
      try {
        source.read(start, block.bytes, block.length, block.replaced);
        read = true;
      } catch (HexSourceChangedException e) {
        clear();
        throw e;
      } finally {
        if (!read) {
          recycle(block);
        }
      }
      insert(block, started);
    }
  }

  /** Drops every cached block, after the source changed. */
  public void clear() {
    lock.lock();
    try {
      generation++;
      free.addAll(blocks.values());
      blocks.clear();
    } finally {
      lock.unlock();
    }
  }

  /** Returns the number of cached blocks. */
  int cachedBlocks() {
    lock.lock();
    try {
      return blocks.size();
    } finally {
      lock.unlock();
    }
  }

  /** Returns the number of block arrays held, cached or free. */
  int heldBlocks() {
    lock.lock();
    try {
      return blocks.size() + free.size();
    } finally {
      lock.unlock();
    }
  }

  private void insert(Block block, long started) {
    lock.lock();
    try {
      if (started != generation || blocks.containsKey(block.index)) {
        free.push(block);
        return;
      }
      if (blocks.size() >= CAPACITY) {
        Iterator<Block> eldest = blocks.values().iterator();
        free.push(eldest.next());
        eldest.remove();
      }
      blocks.put(block.index, block);
    } finally {
      lock.unlock();
    }
  }

  private void recycle(Block block) {
    lock.lock();
    try {
      free.push(block);
    } finally {
      lock.unlock();
    }
  }

  private static long first(long position) {
    return position / BLOCK_BYTES;
  }

  private static long last(long position, int length) {
    return length == 0 ? position / BLOCK_BYTES - 1 : (position + length - 1) / BLOCK_BYTES;
  }
}
