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
    if (key >= F1 && key < F1 + 12) {
      text.append('F').append(key - F1 + 1);
    } else if (key >= UP) {
      text.append(SPECIAL_NAMES[key - UP]);
    } else if (Character.isLetter(key)) {
      text.appendCodePoint(Character.toUpperCase(key));
    } else {
      text.appendCodePoint(key);
    }
    return text.toString();
  }
}
