package io.github.ajdailey.qcaret.app.ipc;

import io.github.ajdailey.qcaret.core.io.AtomicFiles;
import io.github.ajdailey.qcaret.core.io.SafePaths;
import io.github.ajdailey.qcaret.core.util.Streams;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.Properties;

/**
 * The primary instance's {@code instance.endpoint}: {@code version=1}, {@code port=<n>}, {@code
 * token=<64 hex>}. Written owner-only through {@link AtomicFiles#writeOwnerOnly}.
 *
 * @param port the loopback port
 * @param token the shared secret, {@link Protocol#SECRET_BYTES} bytes
 */
record Endpoint(int port, byte[] token) {
  /** The file name in the settings directory. */
  static final String FILE = "instance.endpoint";

  /** The lock file's name in the settings directory. */
  static final String LOCK = "instance.lock";

  private static final int MAX_BYTES = 4096;

  /**
   * Writes the endpoint file.
   *
   * @param directory the settings directory
   * @throws IOException when it cannot be written
   */
  void write(Path directory) throws IOException {
    String text = "version=1\nport=" + port + "\ntoken=" + HexFormat.of().formatHex(token) + "\n";
    AtomicFiles.writeOwnerOnly(
        SafePaths.child(directory, FILE), text.getBytes(StandardCharsets.US_ASCII));
  }

  /**
   * Reads the endpoint file.
   *
   * @param directory the settings directory
   * @return the endpoint
   * @throws java.nio.file.NoSuchFileException when there is no file
   * @throws IOException when it cannot be read or is malformed
   */
  static Endpoint read(Path directory) throws IOException {
    byte[] bytes;
    try (InputStream in = Files.newInputStream(SafePaths.child(directory, FILE))) {
      bytes = Streams.readLimited(in, MAX_BYTES);
    }
    Properties p = new Properties();
    try {
      p.load(new ByteArrayInputStream(bytes));
      if (!"1".equals(p.getProperty("version", "").strip())) {
        throw new IOException("Unknown endpoint version");
      }
      int port = Integer.parseInt(p.getProperty("port", "").strip());
      byte[] token = HexFormat.of().parseHex(p.getProperty("token", "").strip());
      if (port < 1 || port > 65_535 || token.length != Protocol.SECRET_BYTES) {
        throw new IOException("Malformed endpoint");
      }
      return new Endpoint(port, token);
    } catch (IllegalArgumentException e) {
      throw new IOException("Malformed endpoint", e);
    }
  }

  /**
   * Deletes the endpoint file.
   *
   * @param directory the settings directory
   * @throws IOException when it exists and cannot be deleted
   */
  static void delete(Path directory) throws IOException {
    Files.deleteIfExists(SafePaths.child(directory, FILE));
  }
}
