package io.github.ajdailey.qcaret.app.macro;

import java.util.Collection;

/**
 * Saved macro names: 1 to {@value #MAX_LENGTH} chars of letters, digits, space, {@code -} and
 * {@code _}, not starting or ending with a space, and not a Windows device name. Names are unique
 * ignoring case, since the Windows and macOS file systems ignore it.
 */
public final class MacroNames {
  /** Longest name. */
  public static final int MAX_LENGTH = 64;

  /** Why a name is refused. */
  public enum Problem {
    /** Empty, or longer than {@link #MAX_LENGTH}. */
    LENGTH,
    /** A char other than a letter, digit, space, {@code -} or {@code _}, or an outer space. */
    CHARS,
    /** A Windows device name such as {@code CON} or {@code COM1}. */
    DEVICE,
    /** Another macro has the name, ignoring case. */
    TAKEN
  }

  private MacroNames() {}

  /**
   * Checks a name's form.
   *
   * @param name the name
   * @return the problem, or null for a valid name
   */
  public static Problem check(String name) {
    if (name.isEmpty() || name.length() > MAX_LENGTH) {
      return Problem.LENGTH;
    }
    if (name.charAt(0) == ' ' || name.charAt(name.length() - 1) == ' ') {
      return Problem.CHARS;
    }
    for (int i = 0; i < name.length(); ) {
      int c = name.codePointAt(i);
      if (!Character.isLetterOrDigit(c) && c != ' ' && c != '-' && c != '_') {
        return Problem.CHARS;
      }
      i += Character.charCount(c);
    }
    if (name.matches("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])")) {
      return Problem.DEVICE;
    }
    return null;
  }

  /**
   * Checks a name against its form and the names in use.
   *
   * @param name the name
   * @param taken the names of the other macros
   * @return the problem, or null when the name can be used
   */
  public static Problem check(String name, Collection<String> taken) {
    Problem form = check(name);
    if (form != null) {
      return form;
    }
    for (String other : taken) {
      if (same(name, other)) {
        return Problem.TAKEN;
      }
    }
    return null;
  }

  /**
   * Reports whether two names name the same macro.
   *
   * @param a a name
   * @param b another
   * @return true when they are equal ignoring case
   */
  public static boolean same(String a, String b) {
    return String.CASE_INSENSITIVE_ORDER.compare(a, b) == 0;
  }
}
