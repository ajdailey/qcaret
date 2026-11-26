package io.github.ajdailey.qcaret.core.hex;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.core.buffer.RopeStorage;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.text.TextEncoding;
import java.io.IOException;
import java.util.BitSet;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

class HexBlocksTest {
  private static final int BLOCK = HexBlocks.BLOCK_BYTES;

  @Test
  void aLoadReadsOnlyMissingBlocks() throws IOException {
    HexFixtures.ArraySource source = new HexFixtures.ArraySource(new byte[3 * BLOCK + 10]);
    HexBlocks blocks = new HexBlocks(source);
    blocks.load(BLOCK - 5, 10, new CancellationToken());
    assertEquals(2, source.reads);
    blocks.load(BLOCK, 10, new CancellationToken());
    assertEquals(2, source.reads);
    blocks.load(3 * BLOCK, 10, new CancellationToken());
    assertEquals(3, source.reads);
    byte[] tail = new byte[10];
    assertTrue(blocks.copy(3 * BLOCK, tail, 10, null));
  }

  @Test
  void theLeastRecentlyUsedBlockIsEvictedAndItsArraysReused() throws IOException {
    HexFixtures.ArraySource source =
        new HexFixtures.ArraySource(new byte[(HexBlocks.CAPACITY + 4) * BLOCK]);
    HexBlocks blocks = new HexBlocks(source);
    for (int i = 0; i < HexBlocks.CAPACITY; i++) {
      blocks.load((long) i * BLOCK, 1, new CancellationToken());
    }
    byte[] one = new byte[1];
    assertTrue(blocks.copy(0, one, 1, null));
    blocks.load((long) HexBlocks.CAPACITY * BLOCK, 1, new CancellationToken());
    assertTrue(blocks.cached(0, 1));
    assertFalse(blocks.cached(BLOCK, 1));
    assertEquals(HexBlocks.CAPACITY, blocks.cachedBlocks());
    for (int i = HexBlocks.CAPACITY; i < HexBlocks.CAPACITY + 4; i++) {
      blocks.load((long) i * BLOCK, 1, new CancellationToken());
    }
    assertEquals(HexBlocks.CAPACITY + 1, blocks.heldBlocks());
  }

  @Test
  void aBlockReadBeforeAClearIsDropped() throws IOException {
    final HexBlocks[] holder = new HexBlocks[1];
    HexFixtures.ArraySource source =
        new HexFixtures.ArraySource(new byte[2 * BLOCK]) {
          @Override
          public void read(long position, byte[] dst, int length, BitSet replaced) {
            super.read(position, dst, length, replaced);
            if (position == 0) {
              holder[0].clear();
            }
          }
        };
    HexBlocks blocks = new HexBlocks(source);
    holder[0] = blocks;
    blocks.load(0, 2 * BLOCK, new CancellationToken());
    assertFalse(blocks.cached(0, 1));
    assertTrue(blocks.cached(BLOCK, 1));
  }

  @Test
  void aCanceledLoadStopsBetweenBlocks() {
    final HexBlocks blocks = new HexBlocks(new HexFixtures.ArraySource(new byte[2 * BLOCK]));
    final CancellationToken token = new CancellationToken();
    token.cancel();
    assertThrows(
        CancellationException.class,
        new Executable() {
          @Override
          public void execute() throws IOException {
            blocks.load(0, 10, token);
          }
        });
    assertEquals(0, blocks.cachedBlocks());
  }

  @Test
  void aCopyCarriesReplacementMarksAcrossBlocks() throws IOException {
    StringBuilder text = new StringBuilder();
    for (int i = 0; i < BLOCK - 1; i++) {
      text.append('a');
    }
    text.append("€€b");
    EncodedHexSource source =
        new EncodedHexSource(
            ByteOffsets.build(
                RopeStorage.of(text).snapshot(),
                TextEncoding.forName("ISO-8859-1"),
                new CancellationToken()));
    HexBlocks blocks = new HexBlocks(source);
    blocks.load(BLOCK - 2, 4, new CancellationToken());
    byte[] bytes = new byte[4];
    BitSet replaced = new BitSet();
    assertTrue(blocks.copy(BLOCK - 2, bytes, 4, replaced));
    assertArrayEquals(new byte[] {'a', '?', '?', 'b'}, bytes);
    BitSet expected = new BitSet();
    expected.set(1, 3);
    assertEquals(expected, replaced);
  }

  @Test
  void offsetsPastFourGibibytesLoad() throws IOException {
    HexBlocks blocks = new HexBlocks(new HexFixtures.HugeSource());
    long position = HexFixtures.HugeSource.SIZE - 10;
    blocks.load(position, 10, new CancellationToken());
    byte[] bytes = new byte[10];
    assertTrue(blocks.copy(position, bytes, 10, null));
    for (int i = 0; i < 10; i++) {
      assertEquals(HexFixtures.HugeSource.at(position + i), bytes[i]);
    }
  }
}
