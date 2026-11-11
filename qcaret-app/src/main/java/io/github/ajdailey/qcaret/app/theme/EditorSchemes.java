package io.github.ajdailey.qcaret.app.theme;

import io.github.ajdailey.qcaret.app.i18n.UiText;
import java.util.List;

/**
 * The editor color schemes {@code ui.editorColors} names: the {@link Palette} of every
 * palette-colored surface. Native controls follow {@code ui.theme} whatever the scheme. A scheme is
 * added here, as a name, a label and a palette.
 */
public final class EditorSchemes {
  /** The side {@code ui.theme} resolves to; the default. */
  public static final String FOLLOW = "follow";

  /** {@link Palette#LIGHT}. */
  public static final String LIGHT = "light";

  /** {@link Palette#DARK}. */
  public static final String DARK = "dark";

  private static final List<String> NAMES = List.of(FOLLOW, LIGHT, DARK);

  private EditorSchemes() {}

  /**
   * Returns the schemes in the order the Preferences dialog lists them.
   *
   * @return the names, {@link #FOLLOW} first
   */
  public static List<String> names() {
    return NAMES;
  }

  /**
   * Reports whether a name is a scheme.
   *
   * @param name the name, or null
   * @return true for one of {@link #names}
   */
  public static boolean known(String name) {
    return NAMES.contains(name);
  }

  /**
   * Returns a scheme's label for the Preferences dialog.
   *
   * @param name a scheme; an unknown name has {@link #FOLLOW}'s label
   * @return the label
   */
  public static String label(String name) {
    return switch (name) {
      case LIGHT -> UiText.PREFERENCES_EDITOR_COLORS_LIGHT.text();
      case DARK -> UiText.PREFERENCES_EDITOR_COLORS_DARK.text();
      default -> UiText.PREFERENCES_EDITOR_COLORS_FOLLOW.text();
    };
  }

  /**
   * Returns a scheme's built-in palette, before a theme file.
   *
   * @param name a scheme; an unknown name resolves as {@link #FOLLOW}
   * @param mode {@code ui.theme}
   * @param systemDark the system setting
   * @return the palette
   */
  public static Palette palette(String name, ThemeMode mode, boolean systemDark) {
    return switch (name) {
      case LIGHT -> Palette.LIGHT;
      case DARK -> Palette.DARK;
      default -> ThemeController.resolve(mode, systemDark);
    };
  }
}
