package io.github.ajdailey.qcaret.app.command;

/**
 * One key press, free of any UI toolkit. A printable key is the char it types: a letter in lower
 * case with {@link #SHIFT} when shifted, and any other printable char ({@code $}, {@code ^}) as
 * itself without {@link #SHIFT}. Other keys, the space bar included, use the constants below.
 *
 * @param modifiers a combination of {@link #PRIMARY}, {@link #SHIFT}, {@link #ALT} and {@link
 *     #CONTROL}
 * @param key a printable char or one of the key constants
 */
public record KeyStroke(int modifiers, int key) {
  /** Ctrl, or Command on macOS. */
  public static final int PRIMARY = 1;

  /** Shift. */
  public static final int SHIFT = 2;

  /** Alt, or Option on macOS. */
  public static final int ALT = 4;

  /** Control on macOS; elsewhere Ctrl is {@link #PRIMARY}. */
  public static final int CONTROL = 8;

  private static final int SPECIAL = 0x110000;

  /** F1; F2 to F12 follow it. */
  public static final int F1 = SPECIAL + 1;

  /** Up arrow. */
  public static final int UP = SPECIAL + 0x20;

  /** Down arrow. */
  public static final int DOWN = UP + 1;

  /** Left arrow. */
  public static final int LEFT = UP + 2;

  /** Right arrow. */
  public static final int RIGHT = UP + 3;

  /** Page Up. */
  public static final int PAGE_UP = UP + 4;

  /** Page Down. */
  public static final int PAGE_DOWN = UP + 5;

  /** Home. */
  public static final int HOME = UP + 6;

  /** End. */
  public static final int END = UP + 7;

  /** Escape. */
  public static final int ESCAPE = UP + 8;

  /** Enter or Return. */
  public static final int ENTER = UP + 9;

  /** Backspace. */
  public static final int BACKSPACE = UP + 10;

  /** Tab. */
  public static final int TAB = UP + 11;

  /** Delete. */
  public static final int DELETE = UP + 12;

  /** Insert. */
  public static final int INSERT = UP + 13;

  /** Space bar. */
  public static final int SPACE = UP + 14;

  /** Keypad plus. */
  public static final int KEYPAD_ADD = UP + 15;

  /** Keypad minus. */
  public static final int KEYPAD_SUBTRACT = UP + 16;

  private static final int LAST = KEYPAD_SUBTRACT;

  private static final String[] SPECIAL_NAMES = {
    "Up",
    "Down",
    "Left",
    "Right",
    "PgUp",
    "PgDn",
    "Home",
    "End",
    "Esc",
    "Enter",
    "Backspace",
    "Tab",
    "Delete",
    "Insert",
    "Space",
    "Num+",
    "Num-"
  };

  /** Checks the components. */
  public KeyStroke {
    if ((modifiers & ~(PRIMARY | SHIFT | ALT | CONTROL)) != 0) {
      throw new IllegalArgumentException("unknown modifier");
    }
    boolean printable = key > ' ' && key < SPECIAL && !Character.isUpperCase(key);
    boolean function = key >= F1 && key < F1 + 12;
    if (!printable && !function && (key < UP || key > LAST)) {
      throw new IllegalArgumentException("not a key: " + key);
    }
  }

  /**
   * Creates a stroke for a printable char.
   *
   * @param modifiers the modifiers
   * @param c the char; an upper-case letter means the lower-case letter with {@link #SHIFT}
   * @return the stroke
   */
  public static KeyStroke of(int modifiers, char c) {
    if (Character.isUpperCase(c)) {
      return new KeyStroke(modifiers | SHIFT, Character.toLowerCase(c));
    }
    return new KeyStroke(modifiers, c);
  }

  /**
   * Parses {@link #stored()}'s form.
   *
   * @param text for example {@code Mod+Shift+K}, {@code Alt+Left} or {@code F2}
   * @return the stroke, or null when the text is not a stroke
   */
  public static KeyStroke parse(String text) {
    int modifiers = 0;
    String rest = text;
    while (true) {
      if (rest.length() > 4 && rest.startsWith("Mod+")) {
        modifiers |= PRIMARY;
        rest = rest.substring(4);
      } else if (rest.length() > 6 && rest.startsWith("Shift+")) {
        modifiers |= SHIFT;
        rest = rest.substring(6);
      } else if (rest.length() > 4 && rest.startsWith("Alt+")) {
        modifiers |= ALT;
        rest = rest.substring(4);
      } else if (rest.length() > 8 && rest.startsWith("Control+")) {
        modifiers |= CONTROL;
        rest = rest.substring(8);
      } else {
        break;
      }
    }
    int key = keyNamed(rest);
    return key < 0 ? null : new KeyStroke(modifiers, key);
  }

  /** Returns the key a name stands for, or -1. */
  private static int keyNamed(String name) {
    for (int i = 0; i < SPECIAL_NAMES.length; i++) {
      if (SPECIAL_NAMES[i].equals(name)) {
        return UP + i;
      }
    }
    if (name.matches("F([1-9]|1[0-2])")) {
      return F1 + Integer.parseInt(name.substring(1)) - 1;
    }
    if (name.codePointCount(0, name.length()) != 1) {
      return -1;
    }
    int c = Character.toLowerCase(name.codePointAt(0));
    return c > ' ' && c < SPECIAL && !Character.isUpperCase(c) ? c : -1;
  }

  /**
   * Returns the stroke as {@code qcaret.conf} keeps it, the same on every platform: {@code Mod} is
   * {@link #PRIMARY} and {@code Control} is {@link #CONTROL}.
   *
   * @return for example {@code Mod+Shift+K}
   */
  public String stored() {
    StringBuilder text = new StringBuilder();
    if ((modifiers & PRIMARY) != 0) {
      text.append("Mod+");
    }
    if ((modifiers & CONTROL) != 0) {
      text.append("Control+");
    }
    if ((modifiers & ALT) != 0) {
      text.append("Alt+");
    }
    if ((modifiers & SHIFT) != 0) {
      text.append("Shift+");
    }
    return text.append(keyText()).toString();
  }

  /**
   * Reports whether the stroke types text: a printable key or the space bar without Ctrl, Alt or
   * Command. Such a stroke cannot be bound, since it would take the key from the editor.
   *
   * @return true for a key that types
   */
  public boolean types() {
    return (modifiers & (PRIMARY | ALT | CONTROL)) == 0 && (printable() || key == SPACE);
  }

  /**
   * Reports whether the key is a printable char.
   *
   * @return false for the key constants
   */
  public boolean printable() {
    return key < SPECIAL;
  }

  /**
   * Returns the stroke as the platform writes it, for example {@code Ctrl+Shift+G}, {@code Cmd+O},
   * {@code $} or {@code F1}.
   *
   * @param mac true for macOS names
   * @return the text
   */
  public String text(boolean mac) {
    StringBuilder text = new StringBuilder();
    if ((modifiers & CONTROL) != 0) {
      text.append("Ctrl+");
    }
    if ((modifiers & PRIMARY) != 0) {
      text.append(mac ? "Cmd+" : "Ctrl+");
    }
    if ((modifiers & ALT) != 0) {
      text.append(mac ? "Option+" : "Alt+");
    }
    if ((modifiers & SHIFT) != 0) {
      text.append("Shift+");
    }
    return text.append(keyText()).toString();
  }

  private String keyText() {
    if (key >= F1 && key < F1 + 12) {
      return "F" + (key - F1 + 1);
    }
    if (key >= UP) {
      return SPECIAL_NAMES[key - UP];
    }
    return Character.toString(Character.isLetter(key) ? Character.toUpperCase(key) : key);
  }
}
