package io.github.ajdailey.qcaret.ui.swt;

import io.github.ajdailey.qcaret.app.theme.Palette;
import io.github.ajdailey.qcaret.app.theme.Rgb;
import io.github.ajdailey.qcaret.ui.swt.platform.PlatformTraits;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.Tree;

/**
 * The Workspace Browser's colors where the native tree stays light under the dark theme ({@link
 * PlatformTraits#colorsTreesUnderDark}): while the theme setting resolves to dark, the tree and the
 * path and filter fields take {@link Palette#DARK}'s background and text, and the tree's header its
 * gutter and text, whatever the editor color scheme; while it resolves to light they take the
 * system colors. Elsewhere it sets nothing, so cocoa's header colors from {@code CocoaChrome} stay.
 *
 * <p>Under a dark startup setting where controls take the native theme at creation ({@link
 * PlatformTraits#themesAtCreation}), the light side gives the fields the system list colors
 * explicitly. win32 SWT puts a {@code Text} under the dark Explorer theme when it is first colored
 * and never takes it off; with no colors set, Windows draws that field near-black with black text.
 */
final class BrowserColors {
  private BrowserColors() {}

  /**
   * Colors the controls for a side of the theme; called again at each change of side.
   *
   * @param traits the platform
   * @param dark true when the theme setting resolves to dark
   * @param nativeDark true when controls started with the dark native theme ({@link
   *     SwtThemeService#nativeDark})
   * @param tree the tree
   * @param fields the text fields
   */
  static void apply(
      PlatformTraits traits, boolean dark, boolean nativeDark, Tree tree, Text... fields) {
    if (!traits.colorsTreesUnderDark()) {
      return;
    }
    Color background = dark ? color(Palette.DARK.background()) : null;
    Color text = dark ? color(Palette.DARK.text()) : null;
    tree.setBackground(background);
    tree.setForeground(text);
    tree.setHeaderBackground(dark ? color(Palette.DARK.gutter()) : null);
    tree.setHeaderForeground(text);
    Color fieldBackground = background;
    Color fieldText = text;
    if (!dark && nativeDark && traits.themesAtCreation()) {
      fieldBackground = tree.getDisplay().getSystemColor(SWT.COLOR_LIST_BACKGROUND);
      fieldText = tree.getDisplay().getSystemColor(SWT.COLOR_LIST_FOREGROUND);
    }
    for (Text field : fields) {
      field.setBackground(fieldBackground);
      field.setForeground(fieldText);
    }
  }

  private static Color color(Rgb rgb) {
    return new Color(rgb.red(), rgb.green(), rgb.blue());
  }
}
