package io.github.ajdailey.qcaret.app.view;

import io.github.ajdailey.qcaret.app.editor.TokenSource;

/**
 * One editor view of a document as its controller sees it; a split tab holds two. Called on the UI
 * thread.
 */
public interface EditorView {
  /** Scrolls the view so the primary caret shows. */
  void showCaret();

  /** Focuses the view. */
  void focus();

  /**
   * Shows the find matches; replaces the ones shown before.
   *
   * @param ranges model offset pairs, start then end, ascending; empty for none
   */
  void showMatches(int[] ranges);

  /**
   * Returns the view's pixel and column queries.
   *
   * @return the layout
   */
  ViewLayout layout();

  /**
   * Sets the view's columns per tab stop.
   *
   * @param columns from 1 to 16
   */
  void setTabWidth(int columns);

  /**
   * Turns column selection mode on or off: a plain mouse drag then selects a block.
   *
   * @param on true for column mode
   */
  void setColumnMode(boolean on);

  /**
   * Sets the syntax tokens the view paints.
   *
   * @param tokens the document's tokens, or null for none
   */
  void setSyntax(TokenSource tokens);

  /** Repaints the visible text after the syntax tokens changed. */
  void redrawHighlights();
}
