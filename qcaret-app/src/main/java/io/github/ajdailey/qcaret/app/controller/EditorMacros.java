package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.command.CommandId;

/**
 * One editor's link to its window set's {@link MacroHooks}: the hold check for the editor's entry
 * points and the calls that report its input to a recording. A private {@code MacroHooks} stands in
 * until {@link DocumentController} sets the window set's. Confined to the UI thread.
 */
final class EditorMacros {
  private final EditorController editor;
  private MacroHooks hooks = new MacroHooks();

  EditorMacros(EditorController editor) {
    this.editor = editor;
  }

  /** Sets the window set's hooks. */
  void setHooks(MacroHooks value) {
    hooks = value;
  }

  /** Reports whether a macro run holds the document's input: view input is dropped. */
  boolean held() {
    return hooks.held(editor.document());
  }

  /** Reports whether a macro runs on the document; the completion list stays closed. */
  boolean playing() {
    return hooks.playing(editor.document());
  }

  /** Stops the run on the document: Esc while it holds the input. */
  void cancelRun() {
    hooks.cancelRun(editor.document());
  }

  /** Reports a command the editor runs from its own keys. */
  void command(CommandId command) {
    hooks.editorCommand(editor, command);
  }

  /** Reports typed text. */
  void typed(String text) {
    hooks.typed(editor, text);
  }

  /** Reports text the view replaced on its own; {@code composing} for an input method's commit. */
  void viewReplaced(String text, boolean composing) {
    hooks.viewReplaced(editor, text, composing);
  }

  /** Reports a word chosen from the completion list. */
  void completionAccepted(String word) {
    hooks.completed(editor, word);
  }

  /**
   * Reports a mouse action.
   *
   * @return false while a run holds the input, when the action is dropped
   */
  boolean mouse() {
    if (held()) {
      return false;
    }
    hooks.mouse(editor);
    return true;
  }

  /** Shows a notice that ends a worker command with an error; a macro run stops at the step. */
  void failed(String text) {
    editor.notice(text);
    hooks.stepFailed(editor, text);
  }
}
