package io.github.ajdailey.qcaret.app.theme;

/** Receives changes of the system's light or dark setting. */
public interface ThemeListener {
  /**
   * The system setting changed.
   *
   * @param dark true when the system now prefers dark
   */
  void systemThemeChanged(boolean dark);
}
