package io.github.ajdailey.qcaret.app.controller;

import java.util.function.BooleanSupplier;

/**
 * What a user's close of a window's last tab does. With {@code tabs.closeWindowWithLastTab} on, the
 * window closes, so the last window quits. Otherwise an untitled document opens in the window, and
 * an untitled document with no text and no unsaved changes stays open instead of closing. Nothing
 * happens once a quit has begun. Confined to the UI thread.
 */
final class LastTab {
  private final Settings settings;
  private final BooleanSupplier quitting;
  private final Runnable closeWindow;

  /**
   * Creates the rule for one window.
   *
   * @param settings the running settings
   * @param quitting true once the instance or the window has begun to quit
   * @param closeWindow closes the window through {@link WindowSet#requestClose}
   */
  LastTab(Settings settings, BooleanSupplier quitting, Runnable closeWindow) {
    this.settings = settings;
    this.quitting = quitting;
    this.closeWindow = closeWindow;
  }

  /**
   * Reports whether a close of the window's only document leaves it open.
   *
   * @param only the document
   * @return true for an untitled document with no text and no unsaved changes, while closing the
   *     last tab does not close the window
   */
  boolean keeps(OpenDocument only) {
    return only.file.path == null
        && !only.document.isDirty()
        && only.document.text().length() == 0
        && !closesWindow();
  }

  /**
   * Runs after a close left the window without a document: closes the window, or opens an untitled
   * document there.
   *
   * @param documents the window's documents
   */
  void emptied(DocumentController documents) {
    if (quitting.getAsBoolean()) {
      return;
    }
    if (closesWindow()) {
      closeWindow.run();
    } else {
      documents.newDocument();
    }
  }

  private boolean closesWindow() {
    return settings.current().options().closeWindowWithLastTab();
  }
}
