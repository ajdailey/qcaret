package io.github.ajdailey.qcaret.core.io;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;

/**
 * A file's size, modification time and file key, read when a document is loaded or saved. A
 * different stamp at the next save means another program changed the file.
 *
 * @param size the size in bytes
 * @param modified the last modification time
 * @param fileKey the file system's identity for the file (device and inode on POSIX), or null where
 *     the file system has none
 */
public record FileStamp(long size, FileTime modified, Object fileKey) {
  /**
   * Reads a file's stamp, following symbolic links.
   *
   * @param file the file
   * @return the stamp, or null when the file does not exist
   * @throws IOException when the attributes cannot be read
   */
  public static FileStamp read(Path file) throws IOException {
    try {
      BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
      return new FileStamp(attributes.size(), attributes.lastModifiedTime(), attributes.fileKey());
    } catch (NoSuchFileException e) {
      return null;
    }
  }
}
