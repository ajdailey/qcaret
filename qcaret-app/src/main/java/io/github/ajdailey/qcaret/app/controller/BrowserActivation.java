package io.github.ajdailey.qcaret.app.controller;

/**
 * Lists a window's Workspace Browser directories again when the window is activated, so a file
 * another program created or removed shows without Refresh. Each listed directory is read again as
 * Refresh reads it, without the index build and the other windows; a directory whose read is still
 * running keeps that read, so repeated activations run one read per directory. A hidden browser
 * lists nothing. Confined to the UI thread.
 */
final class BrowserActivation {
  private final WorkspaceController workspace;

  /**
   * Creates the activation listing of one window's browser.
   *
   * @param workspace the window's browser
   */
  BrowserActivation(WorkspaceController workspace) {
    this.workspace = workspace;
  }

  /** Lists the browser's idle directories again; the window was activated. */
  void activated() {
    BrowserListings listings = workspace.listings();
    if (workspace.shown() && listings.shownFolder() != null) {
      workspace.relisted(listings.idleDirectories());
    }
  }
}
