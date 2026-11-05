package io.github.ajdailey.qcaret.app.view;

import java.nio.file.Path;

/**
 * One row of the workspace browser. Rows compare by value, so a refreshed listing yields rows equal
 * to the ones it replaces.
 *
 * @param path the entry's path; for {@link Kind#MORE}, {@link Kind#LOADING}, {@link Kind#EMPTY} and
 *     {@link Kind#ERROR}, the directory the row sits in
 * @param name the text shown
 * @param kind what the row is
 */
public record WorkspaceNode(Path path, String name, Kind kind) {
  /** What a row is. */
  public enum Kind {
    /** A directory; expands. */
    DIRECTORY,
    /** A file; activation opens it. */
    FILE,
    /** A link to a directory that contains it; does not expand. */
    CYCLE,
    /** The directory's further entries; activation lists more of them. */
    MORE,
    /** The directory is being listed. */
    LOADING,
    /** The directory lists nothing. */
    EMPTY,
    /** The directory cannot be read; the name says why. */
    ERROR
  }

  /**
   * Reports whether the row expands.
   *
   * @return true for a directory
   */
  public boolean expands() {
    return kind == Kind.DIRECTORY;
  }
}
