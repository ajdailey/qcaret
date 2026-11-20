package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.view.MarkdownViewMode;

/**
 * A tab's saved Markdown view mode until its grammar is known. A mode other than Source applies at
 * once on a Markdown tab, waits while the grammar is detected, and is dropped for any other
 * grammar. Confined to the UI thread.
 */
final class MarkdownRestore {
  private MarkdownViewMode wanted;

  /**
   * Takes a saved mode.
   *
   * @param value the saved mode
   * @param markdown true when the tab's grammar is {@code markdown}
   * @param detecting true while the tab's grammar is not known yet
   * @return the mode to apply now, or null when none applies yet
   */
  MarkdownViewMode restore(MarkdownViewMode value, boolean markdown, boolean detecting) {
    wanted = null;
    if (markdown || value == MarkdownViewMode.SOURCE) {
      return value;
    }
    if (detecting) {
      wanted = value;
    }
    return null;
  }

  /**
   * Hands over the waiting mode once the grammar is known; the mode is dropped either way.
   *
   * @return the waiting mode, or null
   */
  MarkdownViewMode grammarKnown() {
    MarkdownViewMode restored = wanted;
    wanted = null;
    return restored;
  }
}
