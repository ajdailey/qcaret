package io.github.ajdailey.qcaret.app.view;

/** The find bar as the find controller sees it. Called on the UI thread. */
public interface FindListener {
  /**
   * The bar's state changed.
   *
   * @param state the new state
   */
  void findStateChanged(FindState state);

  /** Find or Replace ran: the bar focuses its search field and selects its text. */
  void focusRequested();
}
