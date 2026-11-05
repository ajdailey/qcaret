package io.github.ajdailey.qcaret.app.view;

/**
 * The Bookmarks list's model and actions: the selected document's bookmarks by line. Indexes are
 * read again after each {@link BookmarksView#bookmarksChanged}. Called on the UI thread.
 */
public interface BookmarksInput {
  /**
   * Counts the rows.
   *
   * @return the count
   */
  int rowCount();

  /**
   * Returns a row.
   *
   * @param index the row's index
   * @return the row
   */
  BookmarkRow row(int index);

  /**
   * Describes what the list shows: the document's name and the bookmark count, or that rows were
   * left out.
   *
   * @return one line
   */
  String summary();

  /**
   * Shows a row's bookmarked line in the document's focused view, expanding a collapsed region that
   * hides it.
   *
   * @param index the row's index
   */
  void goTo(int index);

  /**
   * Asks for a row's comment and sets it.
   *
   * @param index the row's index
   */
  void editComment(int index);

  /**
   * Removes a row's bookmark and comment.
   *
   * @param index the row's index
   */
  void remove(int index);

  /** Hides the list. */
  void hide();
}
