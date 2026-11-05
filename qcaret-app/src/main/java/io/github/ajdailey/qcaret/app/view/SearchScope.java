package io.github.ajdailey.qcaret.app.view;

import io.github.ajdailey.qcaret.app.i18n.UiText;

/** What a HyperSearch reads. */
public enum SearchScope {
  /** The selected document. */
  CURRENT_DOCUMENT(UiText.SCOPE_CURRENT_DOCUMENT),
  /** The selections of the selected document's focused view. */
  SELECTION(UiText.SCOPE_SELECTION),
  /** Every open document. */
  OPEN_DOCUMENTS(UiText.SCOPE_OPEN_DOCUMENTS),
  /** Every file under the window's workspace folder. */
  WORKSPACE(UiText.SCOPE_WORKSPACE),
  /** The files directly in a directory. */
  DIRECTORY(UiText.SCOPE_DIRECTORY),
  /** Every file under a directory. */
  TREE(UiText.SCOPE_TREE);

  private final UiText label;

  SearchScope(UiText label) {
    this.label = label;
  }

  /**
   * Returns the dialog's label.
   *
   * @return the label
   */
  public String label() {
    return label.text();
  }

  /**
   * Reports whether the scope reads files from a directory.
   *
   * @return true for the workspace, a directory and a tree
   */
  public boolean folder() {
    return this == WORKSPACE || this == DIRECTORY || this == TREE;
  }
}
