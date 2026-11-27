package io.github.ajdailey.qcaret.app.command;

/**
 * What a key runs: a command, or a saved macro by name. Exactly one component is set.
 *
 * @param command the command, or null
 * @param macro the saved macro's name, or null
 */
public record KeyTarget(CommandId command, String macro) {
  /** Checks that exactly one component is set. */
  public KeyTarget {
    if ((command == null) == (macro == null)) {
      throw new IllegalArgumentException("one of command and macro is required");
    }
  }

  /**
   * Returns the target of a command.
   *
   * @param command the command, or null
   * @return the target, or null for a null command
   */
  public static KeyTarget of(CommandId command) {
    return command == null ? null : new KeyTarget(command, null);
  }

  /**
   * Returns the target of a saved macro.
   *
   * @param name the macro's name, or null
   * @return the target, or null for a null name
   */
  public static KeyTarget macro(String name) {
    return name == null ? null : new KeyTarget(null, name);
  }
}
