package io.github.ajdailey.qcaret.app.view;

/**
 * One line of a replace preview's before and after view.
 *
 * @param kind what the line shows
 * @param line the line number from 1: in the file before the replace for a removed or unchanged
 *     line, after it for an added line; 0 for a gap or a note
 * @param text the line's text without its line break, or the note
 */
public record PreviewLine(Kind kind, int line, String text) {
  /** What a preview line shows. */
  public enum Kind {
    /** An unchanged line around a change. */
    SAME,
    /** A line as it is before the replace. */
    REMOVED,
    /** A line as the replace writes it. */
    ADDED,
    /** Unchanged lines left out between two changes. */
    GAP,
    /** A message instead of lines: why the file is skipped, or that more changes are not shown. */
    NOTE
  }
}
