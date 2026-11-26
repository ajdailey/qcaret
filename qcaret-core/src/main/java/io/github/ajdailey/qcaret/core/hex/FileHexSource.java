package io.github.ajdailey.qcaret.core.hex;

import io.github.ajdailey.qcaret.core.concurrent.WorkerThread;
import io.github.ajdailey.qcaret.core.io.FileStamp;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.BitSet;
import java.util.Objects;

/**
 * A file's bytes on disk, any size. Each read opens the file, reads and closes it, so no handle is
 * held between reads and another program, or QCaret's own save, can replace the file. The {@link
 * FileStamp} taken at {@link #open} is compared before and after each read; a mismatch fails the
 * read with {@link HexSourceChangedException}. Copy as Text and a text pattern use ISO-8859-1, one
 * char per byte.
 */
public final class FileHexSource implements HexSource {
  private final Path file;
  private final FileStamp stamp;

  private FileHexSource(Path file, FileStamp stamp) {
    this.file = file;
    this.stamp = stamp;
  }

  /**
   * Takes a file's stamp; reads nothing.
   *
   * @param file a path the caller checked through {@code SafePaths}
   * @return the source
   * @throws NoSuchFileException when the file does not exist
   * @throws IOException when its attributes cannot be read
   */
  @WorkerThread
  public static FileHexSource open(Path file) throws IOException {
    FileStamp stamp = FileStamp.read(file);
    if (stamp == null) {
      throw new NoSuchFileException(file.toString());
    }
    return new FileHexSource(file, stamp);
  }

  /**
   * Returns the file.
   *
   * @return the path given to {@link #open}
   */
  public Path file() {
    return file;
  }

  /**
   * Returns the stamp taken at {@link #open}.
   *
   * @return the stamp
   */
  public FileStamp stamp() {
    return stamp;
  }

  @Override
  public long size() {
    return stamp.size();
  }

  @Override
  public Charset charset() {
    return StandardCharsets.ISO_8859_1;
  }

  @Override
  @WorkerThread
  public void read(long position, byte[] dst, int length, BitSet replaced) throws IOException {
    Objects.checkFromIndexSize(position, length, size());
    Objects.checkFromIndexSize(0, length, dst.length);
    if (replaced != null) {
      replaced.clear(0, length);
    }
    check();
    try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
      ByteBuffer buffer = ByteBuffer.wrap(dst, 0, length);
      long at = position;
      while (buffer.hasRemaining()) {
        int count = channel.read(buffer, at);
        if (count < 0) {
          throw changed();
        }
        at += count;
      }
    } catch (NoSuchFileException e) {
      throw changed();
    }
    check();
  }

  private void check() throws IOException {
    if (!stamp.equals(FileStamp.read(file))) {
      throw changed();
    }
  }

  private HexSourceChangedException changed() {
    return new HexSourceChangedException("Changed on disk: " + file);
  }
}
