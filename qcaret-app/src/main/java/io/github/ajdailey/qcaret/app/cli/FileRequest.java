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
 * @param heading {@code file.md#heading}: the id of the Markdown heading the caret goes to when no
 *     line is given ({@link HeadingFiles}); null for none
 */
public record FileRequest(
    Path path, int line, int column, boolean readOnly, String encoding, String heading) {
  /**
   * A file with no heading.
   *
   * @param path the path
   * @param line the line, or 0
   * @param column the column, or 0
   * @param readOnly open read-only
   * @param encoding the charset's name, or null
   */
  public FileRequest(Path path, int line, int column, boolean readOnly, String encoding) {
    this(path, line, column, readOnly, encoding, null);
  }

  /**
   * A file with no option.
   *
   * @param path the path
   */
  public FileRequest(Path path) {
    this(path, 0, 0, false, null, null);
  }
}
