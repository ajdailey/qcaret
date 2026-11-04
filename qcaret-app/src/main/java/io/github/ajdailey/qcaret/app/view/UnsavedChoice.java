package io.github.ajdailey.qcaret.app.view;

/** The answer to the unsaved-changes prompt. */
public enum UnsavedChoice {
  /** Save, then go on. */
  SAVE,
  /** Go on without saving. */
  DISCARD,
  /** Stop. */
  CANCEL
}
