package io.github.ajdailey.qcaret.ui.swt.editor;

import io.github.ajdailey.qcaret.app.command.KeyStroke;
import org.eclipse.swt.SWT;

/** Converts between SWT key events and {@link KeyStroke}. */
public final class SwtKeys {
  private static final int[][] SPECIAL = {
    {SWT.ARROW_UP, KeyStroke.UP},
    {SWT.ARROW_DOWN, KeyStroke.DOWN},
    {SWT.ARROW_LEFT, KeyStroke.LEFT},
    {SWT.ARROW_RIGHT, KeyStroke.RIGHT},
    {SWT.PAGE_UP, KeyStroke.PAGE_UP},
    {SWT.PAGE_DOWN, KeyStroke.PAGE_DOWN},
    {SWT.HOME, KeyStroke.HOME},
    {SWT.END, KeyStroke.END},
    {SWT.ESC, KeyStroke.ESCAPE},
    {SWT.CR, KeyStroke.ENTER},
    {SWT.KEYPAD_CR, KeyStroke.ENTER},
    {SWT.BS, KeyStroke.BACKSPACE},
    {SWT.TAB, KeyStroke.TAB},
    {SWT.DEL, KeyStroke.DELETE},
    {SWT.INSERT, KeyStroke.INSERT},
    {' ', KeyStroke.SPACE},
  };

  /** Keypad keys bound as their own keys only with Ctrl or Alt; alone they type their char. */
  private static final int[][] KEYPAD = {
    {SWT.KEYPAD_ADD, KeyStroke.KEYPAD_ADD}, {SWT.KEYPAD_SUBTRACT, KeyStroke.KEYPAD_SUBTRACT},
  };

  private static final int[] FUNCTION = {
    SWT.F1, SWT.F2, SWT.F3, SWT.F4, SWT.F5, SWT.F6, SWT.F7, SWT.F8, SWT.F9, SWT.F10, SWT.F11,
    SWT.F12
  };

  private SwtKeys() {}

  /**
   * Reports whether key names follow macOS.
   *
   * @return true on cocoa
   */
  public static boolean mac() {
    return "cocoa".equals(SWT.getPlatform());
  }

  /**
   * Reports whether the keymap follows Linux, where IBus takes Ctrl+Shift+U.
   *
   * @return true on GTK
   */
  public static boolean linux() {
    return "gtk".equals(SWT.getPlatform());
  }

  /**
   * Converts a key press.
   *
   * @param stateMask the event's modifiers
   * @param keyCode the event's key code
   * @param character the event's char
   * @return the stroke, or null for a modifier key alone or a key QCaret does not bind
   */
  public static KeyStroke stroke(int stateMask, int keyCode, char character) {
    int modifiers = 0;
    if ((stateMask & SWT.MOD1) != 0) {
      modifiers |= KeyStroke.PRIMARY;
    }
    if ((stateMask & SWT.MOD2) != 0) {
      modifiers |= KeyStroke.SHIFT;
    }
    if ((stateMask & SWT.MOD3) != 0) {
      modifiers |= KeyStroke.ALT;
    }
    // MOD4 is Control on macOS and 0 elsewhere.
    if (SWT.MOD4 != 0 && (stateMask & SWT.MOD4) != 0) {
      modifiers |= KeyStroke.CONTROL;
    }
    if ((modifiers & (KeyStroke.PRIMARY | KeyStroke.ALT)) != 0) {
      for (int[] pair : KEYPAD) {
        if (pair[0] == keyCode) {
          return new KeyStroke(modifiers & ~KeyStroke.SHIFT, pair[1]);
        }
      }
    }
    for (int[] pair : SPECIAL) {
      if (pair[0] == keyCode) {
        return new KeyStroke(modifiers, pair[1]);
      }
    }
    for (int i = 0; i < FUNCTION.length; i++) {
      if (FUNCTION[i] == keyCode) {
        return new KeyStroke(modifiers, KeyStroke.F1 + i);
      }
    }
    if ((keyCode & SWT.MODIFIER_MASK) != 0) {
      return null;
    }
    if ((modifiers & (KeyStroke.PRIMARY | KeyStroke.ALT | KeyStroke.CONTROL)) != 0) {
      if (keyCode > ' ' && keyCode < 0x7f) {
        return new KeyStroke(modifiers, Character.toLowerCase(keyCode));
      }
      return null;
    }
    if (character > ' ' && character != 0x7f && !Character.isISOControl(character)) {
      if (Character.isLetter(character)) {
        return KeyStroke.of(modifiers & ~KeyStroke.SHIFT, character);
      }
      return new KeyStroke(modifiers & ~KeyStroke.SHIFT, character);
    }
    return null;
  }

  /**
   * Converts a stroke to a menu accelerator.
   *
   * @param stroke the stroke
   * @return the SWT accelerator value
   */
  public static int accelerator(KeyStroke stroke) {
    int value = 0;
    if ((stroke.modifiers() & KeyStroke.PRIMARY) != 0) {
      value |= SWT.MOD1;
    }
    if ((stroke.modifiers() & KeyStroke.SHIFT) != 0) {
      value |= SWT.MOD2;
    }
    if ((stroke.modifiers() & KeyStroke.ALT) != 0) {
      value |= SWT.MOD3;
    }
    if ((stroke.modifiers() & KeyStroke.CONTROL) != 0) {
      value |= SWT.MOD4;
    }
    int key = stroke.key();
    for (int[] pair : SPECIAL) {
      if (pair[1] == key) {
        return value | pair[0];
      }
    }
    for (int[] pair : KEYPAD) {
      if (pair[1] == key) {
        return value | pair[0];
      }
    }
    if (key >= KeyStroke.F1 && key < KeyStroke.F1 + FUNCTION.length) {
      return value | FUNCTION[key - KeyStroke.F1];
    }
    return value | Character.toUpperCase(key);
  }
}
