package io.github.ajdailey.qcaret.app.view;

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

  /** Cancels a running save of the document and drops the secondary selections. */
  void cancel();

  /**
   * Applies an edit the widget made on its own (drag and drop, middle-click paste, IME).
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
   * Reports the columns that fit the view, the wrap width.
   *
   * @param columns at least 1
   */
  void viewColumnsChanged(int columns);
}
