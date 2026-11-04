package io.github.ajdailey.qcaret.ui.swt;

import org.eclipse.swt.SWT;

/**
 * Turns a command's accelerator, such as {@code Mod+Shift+S}, into an SWT accelerator and its menu
 * text. {@code Mod} is {@link SWT#MOD1}: Command on macOS, Ctrl elsewhere.
 */
final class Accelerators {
  private Accelerators() {}

  /**
   * Parses an accelerator.
   *
   * @param spec modifiers and one key joined by {@code +}, or null
   * @return the SWT accelerator, or 0 for null
   */
  static int parse(String spec) {
    if (spec == null) {
      return 0;
    }
    int value = 0;
    for (String part : spec.split("\\+")) {
      value |=
          switch (part) {
            case "Mod" -> SWT.MOD1;
            case "Shift" -> SWT.SHIFT;
            case "Alt" -> SWT.ALT;
            default -> Character.toUpperCase(part.charAt(0));
          };
    }
    return value;
  }

  /**
   * Returns the text a menu shows after the label.
   *
   * @param spec as {@link #parse}
   * @return for example {@code Ctrl+Shift+S}, or {@code Cmd+Shift+S} on macOS
   */
  static String text(String spec) {
    String mod = "cocoa".equals(SWT.getPlatform()) ? "Cmd" : "Ctrl";
    return spec.replace("Mod", mod);
  }
}
