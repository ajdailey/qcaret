package io.github.ajdailey.qcaret.app.controller;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.io.FileStamp;
import io.github.ajdailey.qcaret.core.search.FileSearch;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@link ResultsNavigation#changed} on a file larger than the digest's block. */
class SearchStaleCheckTest {
  private static final CancellationToken NONE = new CancellationToken();

  @TempDir Path temp;

  private static byte[] bytes(int last) {
    byte[] bytes = new byte[(2 << 20) + 5];
    bytes[bytes.length - 1] = (byte) last;
    return bytes;
  }

  private Path searched(byte[] bytes) throws Exception {
    Path file = Files.write(temp.resolve("data.bin"), bytes);
    Files.setLastModifiedTime(file, FileTime.fromMillis(1_000_000));
    return file;
  }

  private static void touch(Path file) throws Exception {
    Files.setLastModifiedTime(file, FileTime.fromMillis(2_000_000));
  }

  @Test
  void theSameStampIsNotReadAndTheSameBytesAreNotAChange() throws Exception {
    byte[] bytes = bytes(1);
    Path file = searched(bytes);
    FileStamp stamp = FileStamp.read(file);
    assertFalse(ResultsNavigation.changed(file, stamp, FileSearch.digest(bytes), NONE));
    touch(file);
    assertFalse(ResultsNavigation.changed(file, stamp, FileSearch.digest(bytes), NONE));
  }

  @Test
  void otherBytesPastTheFirstBlockAreAChange() throws Exception {
    byte[] bytes = bytes(1);
    Path file = searched(bytes);
    FileStamp stamp = FileStamp.read(file);
    Files.write(file, bytes(2));
    touch(file);
    assertTrue(ResultsNavigation.changed(file, stamp, FileSearch.digest(bytes), NONE));
  }

  @Test
  void aCanceledCheckReportsNoChange() throws Exception {
    byte[] bytes = bytes(1);
    Path file = searched(bytes);
    FileStamp stamp = FileStamp.read(file);
    Files.write(file, bytes(2));
    touch(file);
    CancellationToken token = new CancellationToken();
    token.cancel();
    assertFalse(ResultsNavigation.changed(file, stamp, FileSearch.digest(bytes), token));
  }
}
