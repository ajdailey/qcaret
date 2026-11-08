package io.github.ajdailey.qcaret.app.view;

import java.util.List;

/**
 * The workspace browser's navigation, filter and sort as its view calls them. The shown folder is
 * the tree's top; the workspace folder stays the scope of Quick Open and HyperSearch. Called on the
 * UI thread.
 */
public interface WorkspaceBrowseInput {
  /** A sortable column. */
  enum Column {
    /** The name. */
    NAME,
    /** A file's size. */
    SIZE,
    /** The last-modified time. */
    MODIFIED
  }

  /**
   * Returns the path field's text.
   *
   * @return the shown folder; empty when none
   */
  String shownFolder();

  /**
   * Reports whether the tree shows a folder other than the workspace folder.
   *
   * @return true while browsing
   */
  boolean browsing();

  /**
   * Shows a typed folder: relative to the shown folder, {@code ~} for the home folder. A missing
   * path, a file or a path that does not parse leaves the tree and shows a notice.
   *
   * @param typed the path field's text
   */
  void go(String typed);

  /** Shows the shown folder's parent. */
  void parent();

  /** Shows the user's home folder. */
  void home();

  /** Shows the workspace folder again. */
  void back();

  /**
   * Returns the favorite folders.
   *
   * @return the paths, in the order added
   */
  List<String> favorites();

  /** Adds the shown folder to the favorites. */
  void addFavorite();

  /** Removes the shown folder from the favorites. */
  void removeFavorite();

  /**
   * Returns the file filter.
   *
   * @return {@code ;}-separated globs; empty for every file
   */
  String filter();

  /**
   * Sets the file filter and lists the shown directories again; a glob that does not parse shows a
   * notice and changes nothing.
   *
   * @param globs {@code ;}-separated globs such as {@code *.java;*.md}
   */
  void setFilter(String globs);

  /**
   * Reports whether hidden entries are listed.
   *
   * @return true when they are
   */
  boolean hiddenShown();

  /**
   * Lists hidden entries or leaves them out, and lists the shown directories again.
   *
   * @param shown true to list them
   */
  void setHiddenShown(boolean shown);

  /**
   * Returns the sort column.
   *
   * @return the column
   */
  Column sortColumn();

  /**
   * Reports the sort direction.
   *
   * @return true for descending
   */
  boolean descending();

  /**
   * Sorts by a column and lists the shown directories again.
   *
   * @param column the column
   * @param descending true for descending
   */
  void sort(Column column, boolean descending);
}
