package io.github.ajdailey.qcaret.app.view;

/** An answer to the prompt for a file another program changed. */
public enum ChangeChoice {
  /** Keep the buffer; the next save asks before it overwrites the file. */
  KEEP,
  /** Write the buffer over the changed file; offered when a save found the change. */
  OVERWRITE,
  /** Load the file into the buffer as one undoable edit. */
  RELOAD,
  /** Show the file beside the buffer; the prompt stays open. */
  COMPARE,
  /** Do nothing; offered when a save found the change. */
  CANCEL
}
