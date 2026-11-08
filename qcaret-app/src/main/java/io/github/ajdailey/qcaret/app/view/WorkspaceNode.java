package io.github.ajdailey.qcaret.app.view;

import java.nio.file.Path;
import java.util.Objects;

/**
 * One row of the workspace browser. Rows compare by path, name and kind, so a refreshed listing
 * yields rows equal to the ones it replaces even when a size or a time changed.
 *
 * @param path the entry's path; for {@link Kind#MORE}, {@link Kind#LOADING}, {@link Kind#EMPTY} and
 *     {@link Kind#ERROR}, the directory the row sits in
 * @param name the text shown
 * @param kind what the row is
 * @param size the Size column's text; empty for a directory and the other rows
 * @param modified the Modified column's text; empty for the rows that are not entries
 */
public record WorkspaceNode(Path path, String name, Kind kind, String size, String modified) {
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
   * Creates a row with empty columns.
   *
   * @param path the entry's path, or the directory the row sits in
   * @param name the text shown
   * @param kind what the row is
   */
  public WorkspaceNode(Path path, String name, Kind kind) {
    this(path, name, kind, "", "");
  }

  /**
   * Reports whether the row expands.
   *
   * @return true for a directory
   */
  public boolean expands() {
    return kind == Kind.DIRECTORY;
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof WorkspaceNode node
        && path.equals(node.path)
        && name.equals(node.name)
        && kind == node.kind;
  }

  @Override
  public int hashCode() {
    return Objects.hash(path, name, kind);
  }
}
