package io.github.ajdailey.qcaret.ui.swt.platform;

/**
 * Recreates every main window's controls over its controllers, for {@link
 * ThemeFollow#showOnWindows}; supplied by the window side. Call on the UI thread.
 */
public interface WindowRebuild {
  /** Rebuilds nothing; the theme service holds it until the launcher sets the window side. */
  WindowRebuild NONE =
      new WindowRebuild() {
        @Override
        public void rebuild() {
          // No windows.
        }
      };

  /**
   * Rebuilds every main window on a later UI turn, never inside the handler that called it. A
   * window waits while a dialog that its rebuild would dispose is open.
   */
  void rebuild();
}
