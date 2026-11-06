package io.github.ajdailey.qcaret.app.view;

import java.util.List;

/**
 * What the Preferences dialog's Appearance tab offers.
 *
 * @param themeFiles the theme files by name
 * @param fontFamilies the monospace font families
 */
public record AppearanceChoices(List<String> themeFiles, List<String> fontFamilies) {
  /** Keeps unmodifiable copies. */
  public AppearanceChoices {
    themeFiles = List.copyOf(themeFiles);
    fontFamilies = List.copyOf(fontFamilies);
  }
}
