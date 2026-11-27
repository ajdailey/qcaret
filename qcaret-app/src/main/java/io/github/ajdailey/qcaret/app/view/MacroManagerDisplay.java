package io.github.ajdailey.qcaret.app.view;

/** The open Manage Macros dialog as its controller calls it. UI thread. */
public interface MacroManagerDisplay {
  /** Shows {@link MacroManagerInput#rows} again, keeping the selected name; closed: nothing. */
  void rowsChanged();

  /**
   * Shows a message under the list: a failed file operation.
   *
   * @param text the message
   */
  void showMessage(String text);
}
