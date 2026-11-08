package io.github.ajdailey.qcaret.core.text;

import java.nio.charset.Charset;

/**
 * The settings a file's vim or Emacs modelines declare; each is null, or 0 for the width, when none
 * declares it.
 *
 * @param tabWidth columns per indentation step, 1 to 16, or 0
 * @param insertSpaces true when indentation is spaces, false for tabs
 * @param lineEnding the line ending
 * @param charset the charset
 * @param kind {@code "vim"}, {@code "emacs"}, {@code "vim, emacs"}, or null when no modeline was
 *     found
 */
public record Modeline(
    int tabWidth, Boolean insertSpaces, LineEnding lineEnding, Charset charset, String kind) {
  /** No modeline. */
  public static final Modeline NONE = new Modeline(0, null, null, null, null);
}
