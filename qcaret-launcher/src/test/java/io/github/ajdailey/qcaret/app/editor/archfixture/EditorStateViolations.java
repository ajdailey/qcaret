package io.github.ajdailey.qcaret.app.editor.archfixture;

import io.github.ajdailey.qcaret.app.controller.DocumentController;

/** Editor-state classes that each break one architecture rule; never instantiated. */
final class EditorStateViolations {
  private EditorStateViolations() {}

  /** Uses a controller. */
  static final class UsesController {
    int count(DocumentController documents) {
      return documents.count();
    }
  }
}
