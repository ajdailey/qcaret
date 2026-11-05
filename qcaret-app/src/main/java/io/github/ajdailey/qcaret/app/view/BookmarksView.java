package io.github.ajdailey.qcaret.app.view;

import java.util.function.Consumer;

/** The Bookmarks list dock and the bookmark comment prompt. Called on the UI thread. */
public interface BookmarksView {
  /**
   * Shows the Bookmarks list over its model, or hides it.
   *
   * @param input the list's model
   * @param shown true to show
   */
  void showBookmarks(BookmarksInput input, boolean shown);

  /** Reports a change of the list's rows or summary. */
  void bookmarksChanged();

  /**
   * Asks for a bookmark comment.
   *
   * @param line the zero-based line
   * @param current the comment the field starts with, empty without one
   * @param maxLength the most chars the field takes
   * @param answer receives the text, or null on a cancel
   */
  void askBookmarkComment(int line, String current, int maxLength, Consumer<String> answer);
}
