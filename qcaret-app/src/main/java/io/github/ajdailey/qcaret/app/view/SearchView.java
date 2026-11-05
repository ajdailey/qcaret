package io.github.ajdailey.qcaret.app.view;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/**
 * The HyperSearch dialog, the results dock, the folder chooser, and the replace preview and
 * reports. Called on the UI thread.
 */
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

  /**
   * Shows the replace preview over its model, replacing an open one.
   *
   * @param input the preview's model
   */
  void showReplacePreview(ReplaceInput input);

  /** Reports a change of the replace preview's rows, summary or before and after view. */
  void replacePreviewChanged();

  /**
   * Shows a replace or restore run's report, beside any other open report.
   *
   * @param input the report's model
   */
  void showReplaceReport(ReportInput input);

  /**
   * Reports a change of a report's rows or state.
   *
   * @param input the report that changed
   */
  void replaceReportChanged(ReportInput input);

  /**
   * Asks which replace run to restore.
   *
   * @param runs one line per run, newest first
   * @param chosen receives the chosen run's index; not called when the user cancels
   */
  void chooseReplaceRun(List<String> runs, Consumer<Integer> chosen);
}
