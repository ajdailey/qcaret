package io.github.ajdailey.qcaret.app.view;

import java.util.List;

/** The Quick Open popup's controller as its view calls it. Called on the UI thread. */
public interface QuickOpenInput {
  /**
   * Returns the popup's title.
   *
   * @return Quick Open or Switch Document
   */
  String title();

  /**
   * Matches a query; the rows change at once or, for a large index, when {@link
   * WorkspaceView#quickOpenChanged} is called.
   *
   * @param query the filter text, optionally ending in {@code :line} or {@code :line:column}, or
   *     holding {@code @} and a symbol query
   */
  void filter(String query);

  /**
   * Returns the rows of the last finished match.
   *
   * @return best first
   */
  List<QuickOpenRow> rows();

  /**
   * Returns the line below the rows.
   *
   * @return the match count and the index state
   */
  String summary();

  /**
   * Opens a row's document or file, at the query's line when it gave one; the view closes first.
   *
   * @param index the row
   */
  void choose(int index);

  /** Cancels a running match; the popup closed. */
  void close();

  /**
   * Returns the text the filter field starts with, already matched.
   *
   * @return empty, or {@code @} for Go to Symbol
   */
  String initialQuery();

  /**
   * Returns the heading of the rows' detail column.
   *
   * @return Path, or Kind and line while the rows are symbols
   */
  String detailHeading();
}
