package io.github.ajdailey.qcaret.app.cli;

import java.nio.file.Path;

/**
 * A file to open.
 *
 * @param path the path as given, resolved against the working directory and not yet validated
 * @param line {@code +<line>}: the caret's line from 1, 0 when not given
 * @param column {@code +<line>:<column>}: the caret's column from 1, 0 when not given
 * @param readOnly {@code -r}: open read-only
 * @param encoding {@code -e}: the charset's name, null to detect
 */
public record FileRequest(Path path, int line, int column, boolean readOnly, String encoding) {
  /**
   * A file with no option.
   *
   * @param path the path
   */
  public FileRequest(Path path) {
    this(path, 0, 0, false, null);
  }
}
