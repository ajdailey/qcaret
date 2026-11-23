package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.config.Options;
import io.github.ajdailey.qcaret.app.config.Preferences;

/**
 * The editor font's zoom. {@code editor.fontZoom} holds the size the zoom commands chose and {@code
 * editor.fontSize} the Preferences size; the zoom wins while set. Reset Zoom clears it, and so does
 * a Preferences change of the size or the family.
 */
final class EditorZoom {
  private EditorZoom() {}

  /**
   * Returns the size the editors draw with.
   *
   * @param options the settings
   * @return the zoom, else the Preferences size; 0 for the platform's default
   */
  static int size(Options options) {
    return options.fontZoom() != 0 ? options.fontZoom() : options.fontSize();
  }

  /**
   * Returns the size the editors draw with, in points.
   *
   * @param options the settings
   * @param platformSize the platform's default size in points
   * @return {@link #size}, or {@code platformSize} for 0
   */
  static int points(Options options, int platformSize) {
    int size = size(options);
    return size == 0 ? platformSize : size;
  }

  /**
   * Zooms by whole points, clamped to {@link Options#MIN_FONT_SIZE} and {@link
   * Options#MAX_FONT_SIZE}. A zoom back to the Preferences size, or to {@code platformSize} when
   * that is 0, clears the zoom.
   *
   * @param options the settings
   * @param platformSize the platform's default size in points
   * @param steps points to add; negative zooms out
   * @return the settings with the new zoom
   */
  static Options step(Options options, int platformSize, int steps) {
    int next =
        Math.max(
            Options.MIN_FONT_SIZE,
            Math.min(Options.MAX_FONT_SIZE, points(options, platformSize) + steps));
    int preferred = options.fontSize() == 0 ? platformSize : options.fontSize();
    return options.withFontZoom(next == preferred ? 0 : next);
  }

  /**
   * Returns the zoom a Preferences change keeps.
   *
   * @param before the settings before the change
   * @param fontSize the new Preferences size
   * @param fontFamily the new family
   * @return the zoom when neither changed, else 0
   */
  static int kept(Preferences before, int fontSize, String fontFamily) {
    boolean same =
        fontSize == before.options().fontSize()
            && fontFamily.equals(before.appearance().fontFamily());
    return same ? before.options().fontZoom() : 0;
  }
}
