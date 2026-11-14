package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.view.MainView;
import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import java.nio.file.Path;

/**
 * Opens a file, folder or web address in its system application and, when one took it, holds one
 * {@link io.github.ajdailey.qcaret.app.view.ActivityIndicator} operation for {@link #MILLIS} ms on
 * a UI timer: the application's window appears after the launch call returns, and nothing reports
 * when. Call on the UI thread.
 */
final class LaunchCursor {
  /** Milliseconds the busy cursor shows after a launch. */
  static final int MILLIS = 1000;

  private LaunchCursor() {}

  /**
   * Opens a file or folder through {@link MainView#launch}.
   *
   * @param view launches and shows the cursor
   * @param ui runs the timer
   * @param target the file or folder
   * @return false when no application took it; the cursor does not change
   */
  static boolean launch(MainView view, UiDispatcher ui, Path target) {
    return shown(view, ui, view.launch(target));
  }

  /**
   * Opens a web address through {@link MainView#launchUrl}.
   *
   * @param view launches and shows the cursor
   * @param ui runs the timer
   * @param url the address
   * @return false when no application took it; the cursor does not change
   */
  static boolean launchUrl(MainView view, UiDispatcher ui, String url) {
    return shown(view, ui, view.launchUrl(url));
  }

  private static boolean shown(final MainView view, UiDispatcher ui, boolean launched) {
    if (launched) {
      view.setActive();
      ui.timerExec(
          MILLIS,
          new Runnable() {
            @Override
            public void run() {
              view.setIdle();
            }
          });
    }
    return launched;
  }
}
