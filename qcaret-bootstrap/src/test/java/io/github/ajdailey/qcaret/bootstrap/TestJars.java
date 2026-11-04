package io.github.ajdailey.qcaret.bootstrap;

import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Writes zip files for bootstrap tests. */
final class TestJars {
  private TestJars() {}

  /**
   * Creates a zip at {@code jar} holding each entry of {@code entries}.
   *
   * @param jar the zip to create
   * @param entries entry name to content
   * @throws IOException when the zip cannot be written
   */
  static void write(Path jar, Map<String, byte[]> entries) throws IOException {
    URI uri = URI.create("jar:" + jar.toUri());
    try (FileSystem fs = FileSystems.newFileSystem(uri, Map.of("create", "true"))) {
      for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
        Path path = fs.getPath(entry.getKey());
        if (path.getParent() != null) {
          Files.createDirectories(path.getParent());
        }
        Files.write(path, entry.getValue());
      }
    }
  }
}
