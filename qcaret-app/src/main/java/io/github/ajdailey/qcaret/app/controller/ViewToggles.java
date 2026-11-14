package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.config.Preferences;
import io.github.ajdailey.qcaret.app.config.ViewDefaults;
import io.github.ajdailey.qcaret.app.session.Session;

/**
 * View > Word Wrap, Show Whitespace, Show Line Endings, Overview Strip, Line Numbers and Workspace
 * Browser for one window. The window starts from {@link ViewDefaults}; a session restore then sets
 * its saved state through {@link DocumentController#restoreDisplay} and {@link
 * WorkspaceController#restored} without writing a key. A toggle changes this window only and writes
 * its new state as the default. Confined to the UI thread.
 */
final class ViewToggles {
  private final Settings settings;
  private final DocumentController documents;
  private final WorkspaceController workspace;

  /** Applies the defaults to a window that has no editor yet. */
  ViewToggles(Settings settings, DocumentController documents, WorkspaceController workspace) {
    this.settings = settings;
    this.documents = documents;
    this.workspace = workspace;
    ViewDefaults view = settings.current().state().view();
    documents.restoreDisplay(
        new Session.Display(
            view.wordWrap(),
            view.whitespace(),
            view.lineEndings(),
            view.overview(),
            false,
            view.lineNumbers()));
    if (view.workspaceBrowser() != workspace.shown()) {
      workspace.toggle();
    }
  }

  void toggleWrap() {
    documents.toggleWrap();
    save(defaults().withWordWrap(documents.wrap()));
  }

  void toggleWhitespace() {
    documents.toggleMarks(false);
    save(defaults().withWhitespace(documents.whitespaceShown()));
  }

  void toggleLineEndings() {
    documents.toggleMarks(true);
    save(defaults().withLineEndings(documents.lineEndingsShown()));
  }

  void toggleOverview() {
    documents.toggleOverview();
    save(defaults().withOverview(documents.overviewShown()));
  }

  void toggleLineNumbers() {
    documents.toggleLineNumbers();
    save(defaults().withLineNumbers(documents.lineNumbersShown()));
  }

  void toggleBrowser() {
    workspace.toggle();
    save(defaults().withWorkspaceBrowser(workspace.shown()));
  }

  private ViewDefaults defaults() {
    return settings.current().state().view();
  }

  private void save(ViewDefaults value) {
    Preferences current = settings.current();
    if (!value.equals(current.state().view())) {
      settings.set(current.withState(current.state().withView(value)));
    }
  }
}
