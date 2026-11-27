package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.i18n.UiText;

/**
 * Shows a run's progress in its window's status-bar macro field once per UI turn and clears the
 * field when the run ends, then passes each call on. A counted run shows the running iteration of
 * the count; a run to the end shows the running iteration and the primary selection's share of the
 * document. UI thread.
 */
final class MacroStatus implements MacroPlayer.Listener {
  private final ApplicationController window;
  private final MacroPlayer.Listener next;

  /**
   * Creates the listener.
   *
   * @param window the run's window
   * @param next receives every call after the field updates, or null
   */
  MacroStatus(ApplicationController window, MacroPlayer.Listener next) {
    this.window = window;
    this.next = next;
  }

  /**
   * Returns a run's status text.
   *
   * @param run the run
   * @return for example {@code Macro 12 of 50}
   */
  static String text(MacroPlayer run) {
    return run.count() > 0
        ? UiText.MACRO_STATUS_COUNT.format(Math.min(run.iterations() + 1, run.count()), run.count())
        : UiText.MACRO_STATUS_TO_END.format(run.iterations() + 1, run.share());
  }

  @Override
  public void progress(MacroPlayer run) {
    window.view().showMacroStatus(text(run));
    if (next != null) {
      next.progress(run);
    }
  }

  @Override
  public void ended(MacroPlayer run) {
    window.view().showMacroStatus("");
    if (next != null) {
      next.ended(run);
    }
  }
}
