package io.github.ajdailey.qcaret.ui.swt.platform;

/** How a platform draws a caret image over the text. */
public enum CaretBlend {
  /** Per channel {@code |background - image|}: GTK 3 and cocoa. */
  DIFFERENCE,
  /** Per channel {@code background ^ image}: win32, assumed. */
  XOR,
  /** The image replaces the background: GTK 4. */
  OVER
}
