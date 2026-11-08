package io.github.ajdailey.qcaret.app.controller;

import java.nio.file.Path;

/**
 * An entry a workspace browser operation acts on, and the open files it reaches. Documents hold
 * real paths and watch the path the user opened, so both are compared.
 *
 * @param path the entry's absolute path as the browser lists it
 * @param real its real path; null for a symbolic link or a junction, which an operation acts on
 *     without following
 */
record BrowserPath(Path path, Path real) {
  /**
   * Reports whether an open file lies at or under the entry.
   *
   * @param file the open file
   * @return true when its real path or the path it was opened by does
   */
  boolean covers(OpenFile file) {
    return under(file.path, real) || under(file.given, path) || under(file.given, real);
  }

  static boolean under(Path file, Path entry) {
    return file != null && entry != null && file.startsWith(entry);
  }
}
