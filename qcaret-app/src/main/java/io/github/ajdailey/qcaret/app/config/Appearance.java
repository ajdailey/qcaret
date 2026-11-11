package io.github.ajdailey.qcaret.app.config;

import io.github.ajdailey.qcaret.app.theme.EditorSchemes;
import io.github.ajdailey.qcaret.app.theme.ThemeColors;

/**
 * The theme file, the editor font family and the editor color scheme.
 *
 * @param themeFile {@code ui.themeFile}: the name of a file in the {@code themes} folder without
 *     its {@value ThemeColors#SUFFIX} ending; empty for the built-in colors
 * @param fontFamily {@code editor.fontFamily}: empty for the platform's monospace font
 * @param editorColors {@code ui.editorColors}: an {@link EditorSchemes} name
 */
public record Appearance(String themeFile, String fontFamily, String editorColors) {
  /** Longest {@code fontFamily}. */
  public static final int MAX_FAMILY_CHARS = 128;

  /** The built-in colors following the theme, and the platform's monospace font. */
  public static final Appearance DEFAULTS = new Appearance("", "", EditorSchemes.FOLLOW);

  /** Checks the components. */
  public Appearance {
    if (!validThemeFile(themeFile)
        || !validFamily(fontFamily)
        || !EditorSchemes.known(editorColors)) {
      throw new IllegalArgumentException("invalid theme file, font family or editor colors");
    }
  }

  /**
   * Returns a copy with another editor color scheme.
   *
   * @param value an {@link EditorSchemes} name
   * @return the copy
   */
  public Appearance withEditorColors(String value) {
    return new Appearance(themeFile, fontFamily, value);
  }

  /**
   * Reports whether a value can be {@code themeFile}.
   *
   * @param value the value, or null
   * @return true for an empty string or a {@linkplain ThemeColors#validName valid name}
   */
  public static boolean validThemeFile(String value) {
    return "".equals(value) || ThemeColors.validName(value);
  }

  /**
   * Reports whether a value can be {@code fontFamily}.
   *
   * @param value the value, or null
   * @return false for null, or over {@value #MAX_FAMILY_CHARS} chars
   */
  public static boolean validFamily(String value) {
    return value != null && value.length() <= MAX_FAMILY_CHARS;
  }
}
