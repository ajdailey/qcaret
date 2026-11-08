package io.github.ajdailey.qcaret.app.controller;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Every window's workspace browser, so a change made in one lists the same directories again in the
 * others. Confined to the UI thread.
 */
final class BrowserWindows {
  private final List<WorkspaceController> browsers = new ArrayList<WorkspaceController>();

  void add(WorkspaceController browser) {
    browsers.add(browser);
  }

  void remove(WorkspaceController browser) {
    browsers.remove(browser);
  }

  /**
   * Applies a row operation to every browser but the one that ran it.
   *
   * @param from the browser that ran it
   * @param directory the directory that changed
   * @param removed the entry that went, or null
   * @param added the file an index gains, or null
   * @param folder true when a folder or a link changed
   */
  void changed(WorkspaceController from, Path directory, Path removed, Path added, boolean folder) {
    for (WorkspaceController browser : new ArrayList<WorkspaceController>(browsers)) {
      if (browser != from) {
        browser.apply(directory, removed, added, folder, null);
      }
    }
  }

  /**
   * Lists directories again in every browser but the one refreshed.
   *
   * @param from the browser refreshed
   * @param directories the directories it listed again
   */
  void refreshed(WorkspaceController from, List<Path> directories) {
    for (WorkspaceController browser : new ArrayList<WorkspaceController>(browsers)) {
      if (browser != from) {
        browser.relisted(directories);
      }
    }
  }

  /**
   * Moves every browser's workspace folder and folder browsed to with a rename.
   *
   * @param move the rename
   */
  void moved(PathMove move) {
    for (WorkspaceController browser : new ArrayList<WorkspaceController>(browsers)) {
      browser.follow(move);
    }
  }

  /**
   * Clears every browser's workspace folder that a delete reached.
   *
   * @param entry the deleted entry
   */
  void deleted(BrowserPath entry) {
    for (WorkspaceController browser : new ArrayList<WorkspaceController>(browsers)) {
      browser.clearUnder(entry);
    }
  }
}
