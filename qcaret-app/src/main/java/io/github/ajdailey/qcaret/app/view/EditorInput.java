package io.github.ajdailey.qcaret.app.view;

import io.github.ajdailey.qcaret.app.command.CommandId;

/**
 * What an editor view forwards to its controller. Offsets are display offsets of the view's {@code
 * DisplayProjection}. Called on the UI thread.
 */
public interface EditorInput {
  /**
   * Replaces every selection with typed text.
   *
   * @param text the typed chars
   */
  void type(String text);

  /**
   * Runs an editing command: an {@link io.github.ajdailey.qcaret.app.command.CommandGroup#EDIT} or
   * {@link io.github.ajdailey.qcaret.app.command.CommandGroup#EDITOR} command.
   *
   * @param command the command
   * @return false for a command the editor does not run
   */
  boolean execute(CommandId command);

  /** Replaces every selection with the document's line ending. */
  void newline();

  /** Deletes every selection, or the char before each caret. */
  void backspace();

  /** Deletes every selection, or the char after each caret. */
  void deleteForward();

  /** Copies the selections and deletes them. */
  void cut();

  /** Copies the selections, one per line. */
  void copy();

  /** Replaces the selections with the clipboard's text. */
  void paste();

  /** Undoes the newest step. */
  void undo();

  /** Redoes the step undone last. */
  void redo();

  /** Selects the whole document. */
  void selectAll();

  /**
   * Cancels a running save of the document and drops the secondary selections and the block; with
   * neither, closes the find bar.
   */
  void cancel();

  /**
   * Applies an edit the widget made on its own (drag and drop, middle-click paste).
   *
   * @param start the first display offset replaced
   * @param end the display offset after the last
   * @param text the inserted text
   */
  void replaceFromView(int start, int end, String text);

  /**
   * Sets one selection after the user moved the widget's caret or selection.
   *
   * @param anchor the display offset that stays put
   * @param caret the display offset that moved
   */
  void selectFromView(int anchor, int caret);

  /**
   * Sets several selections the widget holds, after an Alt+click added one.
   *
   * @param ranges display offset pairs, anchor then caret
   * @param primary the index of the pair holding the widget's caret
   */
  void selectionsFromView(int[] ranges, int primary);

  /**
   * Prepares a context menu opened by the pointer: the selections stay when the point lies in a
   * non-empty one; otherwise one caret goes there.
   *
   * @param offset the point's display offset
   */
  void menuFromView(int offset);

  /**
   * Reports the columns that fit the view, the wrap width.
   *
   * @param columns at least 1
   */
  void viewColumnsChanged(int columns);

  /**
   * Selects the block between two points of a mouse drag; the controller reads rows and columns
   * from the view's {@link ViewLayout}.
   *
   * @param anchorX x where the drag started
   * @param anchorY y where the drag started
   * @param x x of the pointer
   * @param y y of the pointer
   */
  void blockFromView(int anchorX, int anchorY, int x, int y);

  /**
   * Selects the block from the primary caret, or the current block's anchor, to a point.
   *
   * @param x x of the click
   * @param y y of the click
   */
  void extendBlockFromView(int x, int y);

  /**
   * Adds a caret at a display offset, or removes the caret there when it is one of several.
   *
   * @param offset the clicked display offset
   */
  void toggleCaretFromView(int offset);

  /**
   * A drag from a {@link #toggleCaretFromView} press: the selections from before the press plus one
   * selection, which becomes the primary. Selections it touches merge into it.
   *
   * @param anchor the display offset of the press
   * @param caret the display offset under the pointer
   */
  void addSelectionFromView(int anchor, int caret);

  /**
   * Replaces the input method's composition text, outside undo; a new composition starts at the
   * primary selection's start. The selection stays: a commit arrives as {@link #type}, which
   * replaces it.
   *
   * @param text the composition text, empty when it ends
   */
  void preeditFromView(String text);

  /**
   * Expands the collapsed region starting on a row's line, or else collapses the region starting
   * there, after a click on its gutter marker.
   *
   * @param row the clicked display row
   */
  void toggleFoldFromView(int row);

  /**
   * Reports the display range the view shows, for find highlights.
   *
   * @param start the display offset of the first visible row
   * @param end the display offset after the last visible row
   */
  void viewportChanged(int start, int end);

  /**
   * Inserts a word from the completion list the view shows.
   *
   * @param index the word's index in the list
   */
  void completionChosen(int index);

  /**
   * Reports the overview strip's height, which its marks are computed for.
   *
   * @param height pixel rows, 0 while hidden
   */
  void overviewResized(int height);
}
