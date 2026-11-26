package io.github.ajdailey.qcaret.core.hex;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.BitSet;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

class FileHexSourceTest {
  /** Larger than the 1 MiB cache and not a whole number of blocks. */
  private static final int SIZE = 3 * HexBlocks.CAPACITY * HexBlocks.BLOCK_BYTES / 2 + 123;

  @TempDir Path dir;

  private static byte[] random(int size, long seed) {
    byte[] bytes = new byte[size];
    new SplittableRandom(seed).nextBytes(bytes);
    return bytes;
  }

  @Test
  void readsAFileLargerThanTheCache() throws IOException {
    byte[] content = random(SIZE, 29);
    Path file = Files.write(dir.resolve("data.bin"), content);
    FileHexSource source = FileHexSource.open(file);
    assertEquals(SIZE, source.size());
    assertEquals(StandardCharsets.ISO_8859_1, source.charset());
    HexBlocks blocks = new HexBlocks(source);
    byte[] row = new byte[64];
    BitSet replaced = new BitSet();
    replaced.set(0, 64);
    long[] positions = {
      0, HexBlocks.BLOCK_BYTES - 32, 7L * HexBlocks.BLOCK_BYTES + 5, SIZE - 64,
    };
    for (long position : positions) {
      assertFalse(blocks.copy(position, row, 64, replaced));
      blocks.load(position, 64, new CancellationToken());
      assertTrue(blocks.copy(position, row, 64, replaced));
      assertArrayEquals(HexFixtures.slice(content, (int) position, 64), row);
      assertTrue(replaced.isEmpty());
    }
    for (long position = 0; position < SIZE; position += HexBlocks.BLOCK_BYTES) {
      int length = (int) Math.min(HexBlocks.BLOCK_BYTES, SIZE - position);
      blocks.load(position, length, new CancellationToken());
    }
    assertEquals(HexBlocks.CAPACITY, blocks.cachedBlocks());
    assertTrue(blocks.cached(SIZE - 1, 1));
    assertFalse(blocks.cached(0, 1));
  }

  @Test
  void aRewriteBetweenReadsFailsTheNextRead() throws IOException {
    final Path file = Files.write(dir.resolve("data.bin"), random(HexBlocks.BLOCK_BYTES + 10, 31));
    final FileHexSource source = FileHexSource.open(file);
    final HexBlocks blocks = new HexBlocks(source);
    blocks.load(0, 10, new CancellationToken());
    Files.write(file, random(HexBlocks.BLOCK_BYTES + 11, 37));
    assertThrows(
        HexSourceChangedException.class,
        new Executable() {
          @Override
          public void execute() throws IOException {
            source.read(0, new byte[10], 10, null);
          }
        });
    assertThrows(
        HexSourceChangedException.class,
        new Executable() {
          @Override
          public void execute() throws IOException {
            blocks.load(HexBlocks.BLOCK_BYTES, 1, new CancellationToken());
          }
        });
    assertEquals(0, blocks.cachedBlocks());
  }

  @Test
  void anotherFileRenamesOverThePathBetweenReads() throws IOException {
    byte[] content = random(5000, 41);
    final Path file = Files.write(dir.resolve("data.bin"), content);
    final FileHexSource source = FileHexSource.open(file);
    byte[] bytes = new byte[100];
    source.read(4000, bytes, 100, null);
    assertArrayEquals(HexFixtures.slice(content, 4000, 100), bytes);
    Path other = Files.write(dir.resolve("other.bin"), random(6000, 43));
    Files.move(other, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    assertEquals(6000, Files.size(file));
    assertThrows(
        HexSourceChangedException.class,
        new Executable() {
          @Override
          public void execute() throws IOException {
            source.read(0, new byte[10], 10, null);
          }
        });
  }

  @Test
  void aDeletedFileReadsAsChanged() throws IOException {
    final Path file = Files.write(dir.resolve("data.bin"), random(100, 47));
    final FileHexSource source = FileHexSource.open(file);
    Files.delete(file);
    assertThrows(
        HexSourceChangedException.class,
        new Executable() {
          @Override
          public void execute() throws IOException {
            source.read(0, new byte[10], 10, null);
          }
        });
    assertThrows(
        NoSuchFileException.class,
        new Executable() {
          @Override
          public void execute() throws IOException {
            FileHexSource.open(file);
          }
        });
  }

  @Test
  void aReadOutsideTheFileIsRefused() throws IOException {
    final FileHexSource source =
        FileHexSource.open(Files.write(dir.resolve("d.bin"), random(10, 53)));
    assertThrows(
        IndexOutOfBoundsException.class,
        new Executable() {
          @Override
          public void execute() throws IOException {
            source.read(5, new byte[10], 6, null);
          }
        });
  }
}
