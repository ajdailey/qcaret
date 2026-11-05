package io.github.ajdailey.qcaret.app.view;

import java.nio.file.Path;
import java.util.function.Consumer;

/** The HyperSearch dialog, the results dock and the folder chooser. Called on the UI thread. */
public interface SearchView {
  /**
   * Shows the HyperSearch dialog without waiting.
   *
   * @param form the fields it opens with
   * @param run receives the fields when the user runs the search; not called on a cancel
   */
  void showSearchDialog(SearchForm form, Consumer<SearchForm> run);

  /**
   * Asks for a folder.
   *
   * @param start the folder the chooser starts in, or null
   * @param chosen receives the folder; not called when the user cancels
   */
  void chooseFolder(Path start, Consumer<Path> chosen);

  /**
   * Shows the results dock over its model, or hides it.
   *
   * @param results the dock's model
   * @param shown true to show
   */
  void showResults(ResultsInput results, boolean shown);

  /**
   * Reports a change of the results.
   *
   * @param structure true when sets, groups or rows changed; false when only the rows of running
   *     sets changed and their group counts grew
   */
  void resultsChanged(boolean structure);

  /**
   * Shows and selects a match's row.
   *
   * @param set the set's index
   * @param file the group's index
   * @param match the match's index
   */
  void revealResult(int set, int file, int match);
}
