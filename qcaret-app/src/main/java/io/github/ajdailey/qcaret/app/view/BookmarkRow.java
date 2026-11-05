package io.github.ajdailey.qcaret.app.view;

/**
 * One row of the Bookmarks list.
 *
 * @param line the zero-based line
 * @param comment the bookmark's comment, empty without one
 * @param text the line's first chars, tabs and control chars shown as spaces
 */
public record BookmarkRow(int line, String comment, String text) {}
