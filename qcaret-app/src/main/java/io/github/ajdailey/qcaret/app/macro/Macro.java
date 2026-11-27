package io.github.ajdailey.qcaret.app.macro;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.command.KeyStroke;
import java.util.List;

/**
 * A macro: its steps in order, and for a saved one its name and optional key.
 *
 * @param name the name, or null for the recorded macro
 * @param key the key that runs it, or null
 * @param steps the steps, unmodifiable
 */
public record Macro(String name, KeyStroke key, List<MacroStep> steps) {
  /** Copies the steps. */
  public Macro {
    steps = List.copyOf(steps);
  }

  /**
   * Returns the macro under a name.
   *
   * @param newName the name
   * @return the copy
   */
  public Macro named(String newName) {
    return new Macro(newName, key, steps);
  }

  /**
   * Reports whether a step runs Undo or Redo. Such a macro plays without the run's single undo
   * group, since Undo cannot run inside an open group.
   *
   * @return true when a step is {@link CommandId#UNDO} or {@link CommandId#REDO}
   */
  public boolean undoes() {
    for (MacroStep step : steps) {
      if (step instanceof MacroStep.Command c
          && (c.command() == CommandId.UNDO || c.command() == CommandId.REDO)) {
        return true;
      }
    }
    return false;
  }
}
