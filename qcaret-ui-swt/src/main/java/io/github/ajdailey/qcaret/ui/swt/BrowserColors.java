package io.github.ajdailey.qcaret.ui.swt;

import io.github.ajdailey.qcaret.app.theme.Palette;
import io.github.ajdailey.qcaret.app.theme.Rgb;
import io.github.ajdailey.qcaret.ui.swt.platform.PlatformTraits;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.Tree;

/**
 * The Workspace Browser's colors where the native tree stays light under the dark theme ({@link
 * PlatformTraits#colorsTreesUnderDark}): while the theme setting resolves to dark, the tree and the
 * path and filter fields take {@link Palette#DARK}'s background and text, and the tree's header its
 * gutter and text, whatever the editor color scheme; while it resolves to light they take the
 * system colors. Elsewhere it sets nothing, so cocoa's header colors from {@code CocoaChrome} stay.
 */
final class BrowserColors {
  private BrowserColors() {}

  /**
   * Colors the controls for a side of the theme; called again at each change of side.
   *
   * @param traits the platform
   * @param dark true when the theme setting resolves to dark
   * @param tree the tree
   * @param fields the text fields
   */
  static void apply(PlatformTraits traits, boolean dark, Tree tree, Text... fields) {
    if (!traits.colorsTreesUnderDark()) {
      return;
    }
    Color background = dark ? color(Palette.DARK.background()) : null;
    Color text = dark ? color(Palette.DARK.text()) : null;
    tree.setBackground(background);
    tree.setForeground(text);
    tree.setHeaderBackground(dark ? color(Palette.DARK.gutter()) : null);
    tree.setHeaderForeground(text);
    for (Text field : fields) {
      field.setBackground(background);
      field.setForeground(text);
    }
  }

  private static Color color(Rgb rgb) {
    return new Color(rgb.red(), rgb.green(), rgb.blue());
  }
}
