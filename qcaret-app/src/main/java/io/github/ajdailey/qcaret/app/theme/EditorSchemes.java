package io.github.ajdailey.qcaret.app.theme;

import io.github.ajdailey.qcaret.app.i18n.UiText;
import java.util.List;

/**
 * The editor color schemes {@code ui.editorColors} names: the {@link Palette} of every
 * palette-colored surface. Native controls follow {@code ui.theme} whatever the scheme. A scheme is
 * added here, as a name, a label and a palette. A theme file named like a scheme is not listed.
 */
public final class EditorSchemes {
  /** The side {@code ui.theme} resolves to; the default. */
  public static final String FOLLOW = "follow";

  /** {@link Palette#LIGHT}. */
  public static final String LIGHT = "light";

  /** {@link Palette#DARK}. */
  public static final String DARK = "dark";

  /** {@link BundledSchemes#SANDSTONE}. */
  public static final String SANDSTONE = "sandstone";

  /** {@link BundledSchemes#NIGHTWATER}. */
  public static final String NIGHTWATER = "nightwater";

  /** {@link BundledSchemes#GARDEN_LIGHT}. */
  public static final String GARDEN_LIGHT = "garden-light";

  /** {@link BundledSchemes#GARDEN_DARK}. */
  public static final String GARDEN_DARK = "garden-dark";

  /** {@link BundledSchemes#LEGAL_PAD}. */
  public static final String LEGAL_PAD = "legal-pad";

  /** {@link BundledSchemes#PAPER}. */
  public static final String PAPER = "paper";

  /** {@link BundledSchemes#MIDNIGHT}. */
  public static final String MIDNIGHT = "midnight";

  /** {@link BundledSchemes#PHOSPHOR}. */
  public static final String PHOSPHOR = "phosphor";

  /** {@link BundledSchemes#GRAPHITE}. */
  public static final String GRAPHITE = "graphite";

  private static final List<String> NAMES =
      List.of(
          FOLLOW,
          LIGHT,
          DARK,
          SANDSTONE,
          NIGHTWATER,
          GARDEN_LIGHT,
          GARDEN_DARK,
          LEGAL_PAD,
          PAPER,
          MIDNIGHT,
          PHOSPHOR,
          GRAPHITE);

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
      case SANDSTONE -> UiText.PREFERENCES_EDITOR_COLORS_SANDSTONE.text();
      case NIGHTWATER -> UiText.PREFERENCES_EDITOR_COLORS_NIGHTWATER.text();
      case GARDEN_LIGHT -> UiText.PREFERENCES_EDITOR_COLORS_GARDEN_LIGHT.text();
      case GARDEN_DARK -> UiText.PREFERENCES_EDITOR_COLORS_GARDEN_DARK.text();
      case LEGAL_PAD -> UiText.PREFERENCES_EDITOR_COLORS_LEGAL_PAD.text();
      case PAPER -> UiText.PREFERENCES_EDITOR_COLORS_PAPER.text();
      case MIDNIGHT -> UiText.PREFERENCES_EDITOR_COLORS_MIDNIGHT.text();
      case PHOSPHOR -> UiText.PREFERENCES_EDITOR_COLORS_PHOSPHOR.text();
      case GRAPHITE -> UiText.PREFERENCES_EDITOR_COLORS_GRAPHITE.text();
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
      case SANDSTONE -> BundledSchemes.SANDSTONE;
      case NIGHTWATER -> BundledSchemes.NIGHTWATER;
      case GARDEN_LIGHT -> BundledSchemes.GARDEN_LIGHT;
      case GARDEN_DARK -> BundledSchemes.GARDEN_DARK;
      case LEGAL_PAD -> BundledSchemes.LEGAL_PAD;
      case PAPER -> BundledSchemes.PAPER;
      case MIDNIGHT -> BundledSchemes.MIDNIGHT;
      case PHOSPHOR -> BundledSchemes.PHOSPHOR;
      case GRAPHITE -> BundledSchemes.GRAPHITE;
      default -> ThemeController.resolve(mode, systemDark);
    };
  }
}
