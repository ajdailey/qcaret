package io.github.ajdailey.qcaret.core.io;

import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.CpuSteps;
import io.github.ajdailey.qcaret.core.concurrent.WorkerThread;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** SHA-256 digests as lower-case hex, for {@link FileBaseline}. */
public final class Digests {
  private static final int BLOCK_BYTES = 1 << 20;

  private Digests() {}

  /**
   * Creates a SHA-256 digest.
   *
   * @return a new digest
   */
  public static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required of every Java runtime", e);
    }
  }

  /**
   * Finishes a digest as hex.
   *
   * @param digest the digest, reset by this call
   * @return 64 lower-case hex digits
   */
  public static String hex(MessageDigest digest) {
    return HexFormat.of().formatHex(digest.digest());
  }

  /**
   * Digests bytes.
   *
   * @param bytes the bytes
   * @param length how many, from the start
   * @return 64 lower-case hex digits
   */
  public static String of(byte[] bytes, int length) {
    MessageDigest digest = sha256();
    digest.update(bytes, 0, length);
    return hex(digest);
  }

  /**
   * Digests a file, one block of 1 MiB at a time.
   *
   * @param file the file; a symbolic link is followed
   * @param token checked before each block
   * @return 64 lower-case hex digits
   * @throws IOException when the file cannot be read
   * @throws java.util.concurrent.CancellationException when canceled
   */
  @WorkerThread
  public static String of(Path file, CancellationToken token) throws IOException {
    return HexFormat.of().formatHex(bytesOf(file, token));
  }

  /**
   * Digests a file, one block of 1 MiB at a time: the calling thread reads, and each block is
   * digested as a {@link CpuSteps} step.
   *
   * @param file the file; a symbolic link is followed
   * @param token checked before each block
   * @return the 32 digest bytes
   * @throws IOException when the file cannot be read
   * @throws java.util.concurrent.CancellationException when canceled
   */
  @WorkerThread
  public static byte[] bytesOf(Path file, CancellationToken token) throws IOException {
    final MessageDigest digest = sha256();
    final ByteBuffer buffer = ByteBuffer.allocate(BLOCK_BYTES);
    try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
      while (true) {
        token.check();
        buffer.clear();
        if (channel.read(buffer) < 0) {
          break;
        }
        buffer.flip();
        update(digest, buffer, token);
      }
    }
    return digest.digest();
  }

  /**
   * Adds a buffer's remaining bytes to a digest as a {@link CpuSteps} step.
   *
   * @param digest the digest
   * @param buffer the bytes; consumed
   * @param token stops a wait for a queued step
   * @throws java.util.concurrent.CancellationException when canceled before the step ran
   */
  public static void update(
      final MessageDigest digest, final ByteBuffer buffer, CancellationToken token) {
    CpuSteps.compute(
        token,
        new CpuSteps.Compute<Void>() {
          @Override
          public Void run() {
            digest.update(buffer);
            return null;
          }
        });
  }
}
