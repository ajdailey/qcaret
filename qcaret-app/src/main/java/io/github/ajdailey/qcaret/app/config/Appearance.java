package io.github.ajdailey.qcaret.app.config;

import io.github.ajdailey.qcaret.app.theme.EditorSchemes;
import io.github.ajdailey.qcaret.app.theme.ThemeColors;

/**
 * The editor colors and the editor font family. {@code ui.editorColors} stores {@link #colors}: a
 * scheme, or a theme file laid over {@link EditorSchemes#FOLLOW}'s palette.
 *
 * @param themeFile the name of a file in the {@code themes} folder without its {@value
 *     ThemeColors#SUFFIX} ending; empty for a scheme alone
 * @param fontFamily {@code editor.fontFamily}: empty for the platform's monospace font
 * @param editorColors an {@link EditorSchemes} name; {@link EditorSchemes#FOLLOW} with a theme file
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
        || !EditorSchemes.known(editorColors)
        || !(themeFile.isEmpty() || EditorSchemes.FOLLOW.equals(editorColors))) {
      throw new IllegalArgumentException("invalid theme file, font family or editor colors");
    }
  }

  /**
   * Returns the editor colors as one value.
   *
   * @return {@link #themeFile} when set, else {@link #editorColors}
   */
  public String colors() {
    return themeFile.isEmpty() ? editorColors : themeFile;
  }

  /**
   * Returns a copy with the editor colors given as one value.
   *
   * @param value an {@link EditorSchemes} name, or a theme file's name that is no scheme's
   * @return the copy
   */
  public Appearance withColors(String value) {
    return EditorSchemes.known(value)
        ? new Appearance("", fontFamily, value)
        : new Appearance(value, fontFamily, EditorSchemes.FOLLOW);
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
