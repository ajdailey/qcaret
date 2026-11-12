package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.config.Preferences;
import io.github.ajdailey.qcaret.app.config.UiState;
import io.github.ajdailey.qcaret.app.view.MainView;

/**
 * View > Indent Guides and View > Modified Line Marks, saved as {@code ui.indentGuides} and {@code
 * ui.lineChanges} and passed to the window's view, and Edit > Line > Wrap Paragraph at {@code
 * editor.rightMargin}. Confined to the UI thread.
 */
final class EditorAids {
  private final Settings settings;
  private final MainView view;
  private final DocumentController documents;

  EditorAids(Settings settings, MainView view, DocumentController documents) {
    this.settings = settings;
    this.view = view;
    this.documents = documents;
    UiState state = settings.current().state();
    view.setIndentGuides(state.indentGuides());
    view.setLineChangesShown(state.lineChanges());
  }

  /** Passes changed toggles to the view. */
  void apply(Preferences before, Preferences after) {
    if (after.state().indentGuides() != before.state().indentGuides()) {
      view.setIndentGuides(after.state().indentGuides());
    }
    if (after.state().lineChanges() != before.state().lineChanges()) {
      view.setLineChangesShown(after.state().lineChanges());
    }
  }

  /** Flips {@code ui.indentGuides}. */
  void toggleIndentGuides() {
    Preferences current = settings.current();
    UiState state = current.state();
    settings.set(current.withState(state.withIndentGuides(!state.indentGuides())));
  }

  /** Flips {@code ui.lineChanges}. */
  void toggleLineChanges() {
    Preferences current = settings.current();
    UiState state = current.state();
    settings.set(current.withState(state.withLineChanges(!state.lineChanges())));
  }

  /** Wraps the selected editor's paragraphs at {@code editor.rightMargin}. */
  void wrapParagraph() {
    EditorController editor = documents.activeEditor();
    if (editor != null) {
      editor.wrapParagraph(settings.current().options().rightMargin());
    }
  }
}
