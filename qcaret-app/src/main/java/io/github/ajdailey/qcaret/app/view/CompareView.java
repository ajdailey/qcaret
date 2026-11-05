package io.github.ajdailey.qcaret.app.view;

import java.util.List;
import java.util.function.Consumer;

/** The prompts and windows of external change detection and file comparison. */
public interface CompareView {
  /**
   * Asks what to do about a file another program changed. Without {@code saving} the choices are
   * {@link ChangeChoice#KEEP}, {@link ChangeChoice#RELOAD} and {@link ChangeChoice#COMPARE}, and
   * closing the prompt answers {@code KEEP}; with it, {@link ChangeChoice#OVERWRITE}, {@code
   * RELOAD}, {@code COMPARE} and {@link ChangeChoice#CANCEL}, and closing answers {@code CANCEL}.
   * {@code COMPARE} leaves the prompt open, so the answer can arrive more than once; every other
   * choice closes it and is the last answer.
   *
   * @param name the document's name
   * @param saving true when a save found the change
   * @param answer receives the choices
   */
  void askExternalChange(String name, boolean saving, Consumer<ChangeChoice> answer);

  /**
   * Asks which open document to compare the selected one with.
   *
   * @param names the other documents' labels
   * @param chosen receives the chosen index; not called on cancel
   */
  void chooseDocument(List<String> names, Consumer<Integer> chosen);

  /**
   * Opens a read-only window showing a comparison.
   *
   * @param comparison the rows
   */
  void showComparison(Comparison comparison);
}
