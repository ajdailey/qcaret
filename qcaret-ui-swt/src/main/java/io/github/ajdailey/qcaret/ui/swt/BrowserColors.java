package io.github.ajdailey.qcaret.ui.swt;

import io.github.ajdailey.qcaret.app.theme.Palette;
import io.github.ajdailey.qcaret.app.theme.Rgb;
import io.github.ajdailey.qcaret.ui.swt.platform.PlatformTraits;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.Tree;

/**
 * The Workspace Browser's colors where the native tree stays light under the dark theme ({@link
 * PlatformTraits#colorsTreesUnderDark}): while the theme setting resolves to dark, the tree and the
 * path and filter fields take {@link Palette#DARK}'s background and text, and the tree's header its
 * gutter and text, whatever the editor color scheme; while it resolves to light they take the
 * system colors. A field in {@link Palette#DARK}'s colors draws its hint in the palette's
 * unselected tab label color ({@link FieldHint}), 5.9:1 on its background. Elsewhere it sets
 * nothing, so cocoa's header colors from {@code CocoaChrome} stay.
 *
 * <p>Under a dark startup setting where controls take the native theme at creation ({@link
 * PlatformTraits#themesAtCreation}), a field holding text keeps {@link Palette#DARK}'s background
 * and text on the light side until the next start, while the tree and its header follow the theme:
 * a field holding text under the dark native theme draws near-black with dark text under any light
 * colors. An empty field, showing only its hint, takes the system colors there; {@link #follow}
 * colors the fields again when one becomes empty or takes text. The path field is enabled and empty
 * while no workspace folder is set.
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
   * @param fields the text fields' hints
   */
  static void apply(
      PlatformTraits traits, boolean dark, boolean nativeDark, Tree tree, FieldHint... fields) {
    if (!traits.colorsTreesUnderDark()) {
      return;
    }
    Color background = dark ? color(Palette.DARK.background()) : null;
    Color text = dark ? color(Palette.DARK.text()) : null;
    tree.setBackground(background);
    tree.setForeground(text);
    tree.setHeaderBackground(dark ? color(Palette.DARK.gutter()) : null);
    tree.setHeaderForeground(text);
    boolean darkStart = nativeDark && traits.themesAtCreation();
    for (FieldHint field : fields) {
      boolean darkField = dark || darkStart && field.field().getCharCount() > 0;
      field.field().setBackground(darkField ? color(Palette.DARK.background()) : null);
      field.field().setForeground(darkField ? color(Palette.DARK.text()) : null);
      field.setColor(darkField ? color(Palette.DARK.accents().tabText()) : null);
    }
  }

  /**
   * Runs a recoloring each time a field becomes empty or takes text.
   *
   * @param field the field
   * @param recolor calls {@link #apply}
   */
  static void follow(final Text field, final Runnable recolor) {
    field.addListener(
        SWT.Modify,
        new Listener() {
          private boolean empty = field.getCharCount() == 0;

          @Override
          public void handleEvent(Event event) {
            boolean now = field.getCharCount() == 0;
            if (now != empty) {
              empty = now;
              recolor.run();
            }
          }
        });
  }

  private static Color color(Rgb rgb) {
    return new Color(rgb.red(), rgb.green(), rgb.blue());
  }
}
