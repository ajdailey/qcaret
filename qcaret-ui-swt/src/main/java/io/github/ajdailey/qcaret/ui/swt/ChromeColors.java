package io.github.ajdailey.qcaret.ui.swt;

import io.github.ajdailey.qcaret.app.theme.Palette;
import io.github.ajdailey.qcaret.app.theme.Rgb;
import io.github.ajdailey.qcaret.ui.swt.editor.EditorPane;
import io.github.ajdailey.qcaret.ui.swt.platform.PlatformTraits;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.swt.SWT;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Link;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.ProgressBar;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Spinner;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.Tree;

/**
 * The main window's own surfaces where the native theme leaves them light under a dark palette
 * ({@link PlatformTraits#colorsChromeUnderDark}). While the theme setting resolves to dark, each
 * control under a surface given to {@link #add} takes {@link Palette#DARK}'s gutter as background
 * and its text as foreground, a link its link color raised to 4.5 contrast, and each surface passes
 * its background to push button corners ({@code SWT.INHERIT_DEFAULT}). While it resolves to light
 * every such color is null. The colors follow a live switch, since own colors need no restart.
 *
 * <p>Push buttons, arrow buttons, fields, combos, lists, tables, trees, spinners, progress bars, a
 * {@link StyledText}, a {@link Browser}, a {@link CTabFolder} and a control with {@link
 * EditorPane#PALETTE_COLORED} keep their colors, and the walk does not descend into them. A control
 * created later under a surface takes its colors from {@link SWT#Skin}. After each change every
 * {@link ToolIcons} given to {@link #add(ToolIcons)} re-inks from its source and gives disabled
 * items faded copies under dark. Elsewhere nothing changes. Confined to the UI thread.
 */
final class ChromeColors {
  /** Background of every surface under dark: {@link Palette#DARK}'s gutter, the tab strip's. */
  static final Rgb BACKGROUND = Palette.DARK.gutter();

  /** Text under dark. */
  static final Rgb TEXT = Palette.DARK.text();

  private final PlatformTraits traits;
  private final Display display;
  private final Listener skin =
      new Listener() {
        @Override
        public void handleEvent(Event event) {
          if (dark
              && event.widget instanceof Control control
              && !control.isDisposed()
              && under(control)) {
            walk(control);
          }
        }
      };
  private final List<Control> surfaces = new ArrayList<Control>();
  private final List<ToolIcons> icons = new ArrayList<ToolIcons>();
  private boolean dark;
  private boolean painted;

  /**
   * Creates the colors for a window's Display.
   *
   * @param traits the platform
   * @param display the Display
   */
  ChromeColors(PlatformTraits traits, Display display) {
    this.traits = traits;
    this.display = display;
    if (traits.colorsChromeUnderDark()) {
      display.addListener(SWT.Skin, skin);
    }
  }

  /** Stops coloring new controls; call when the window is disposed. */
  void dispose() {
    if (!display.isDisposed()) {
      display.removeListener(SWT.Skin, skin);
    }
  }

  /**
   * Colors a surface and its controls for the current side, and again at each {@link #apply}, until
   * the surface is disposed.
   *
   * @param surface the surface
   */
  void add(final Control surface) {
    if (!traits.colorsChromeUnderDark()) {
      return;
    }
    surfaces.add(surface);
    surface.addListener(
        SWT.Dispose,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            surfaces.remove(surface);
          }
        });
    if (dark) {
      walk(surface);
    }
  }

  /**
   * Re-inks an icon set after each {@link #apply} and gives its disabled items faded copies under
   * dark.
   *
   * @param set the icons, inked from a control under a surface
   */
  void add(ToolIcons set) {
    if (traits.colorsChromeUnderDark()) {
      icons.add(set);
      set.fadeDisabled(dark);
    }
  }

  /**
   * Colors every surface for a side of the theme.
   *
   * @param value true when the theme setting resolves to dark
   */
  void apply(boolean value) {
    if (!traits.colorsChromeUnderDark() || (!value && !painted)) {
      return;
    }
    dark = value;
    painted |= value;
    for (Control surface : new ArrayList<Control>(surfaces)) {
      if (!surface.isDisposed()) {
        walk(surface);
      }
    }
    for (ToolIcons set : icons) {
      set.fadeDisabled(value);
      set.reink();
    }
  }

  /**
   * Reports whether the surfaces carry the dark colors.
   *
   * @return true under dark where the platform colors them
   */
  boolean dark() {
    return dark;
  }

  private void walk(Control control) {
    if (keeps(control)) {
      return;
    }
    style(control);
    if (control instanceof Composite composite) {
      composite.setBackgroundMode(dark ? SWT.INHERIT_DEFAULT : SWT.INHERIT_NONE);
      for (Control child : composite.getChildren()) {
        walk(child);
      }
    }
  }

  /** Reports whether a new control sits under a surface with no kept control between. */
  private boolean under(Control control) {
    for (Control at = control; at != null && !(at instanceof Shell); at = at.getParent()) {
      if (keeps(at)) {
        return false;
      }
      if (surfaces.contains(at)) {
        return true;
      }
    }
    return false;
  }

  private void style(Control control) {
    if (keeps(control)) {
      return;
    }
    control.setBackground(dark ? color(BACKGROUND) : null);
    control.setForeground(dark ? color(TEXT) : null);
    if (control instanceof Link link) {
      link.setLinkForeground(dark ? linkColor(link) : null);
    }
  }

  /** Reports whether a control keeps its own colors. */
  private static boolean keeps(Control control) {
    if (control.getData(EditorPane.PALETTE_COLORED) != null) {
      return true;
    }
    if (control instanceof Button) {
      return (control.getStyle() & (SWT.CHECK | SWT.RADIO)) == 0;
    }
    return control instanceof Text
        || control instanceof Combo
        || control instanceof org.eclipse.swt.widgets.List
        || control instanceof Table
        || control instanceof Tree
        || control instanceof Spinner
        || control instanceof ProgressBar
        || control instanceof StyledText
        || control instanceof Browser
        || control instanceof CTabFolder;
  }

  /** Returns the link color raised to 4.5 contrast on the dark background. */
  static Color linkColor(Control link) {
    RGB theme = link.getDisplay().getSystemColor(SWT.COLOR_LINK_FOREGROUND).getRGB();
    return new Color(SwtThemeService.readable(theme, color(BACKGROUND).getRGB()));
  }

  static Color color(Rgb rgb) {
    return new Color(new RGB(rgb.red(), rgb.green(), rgb.blue()));
  }
}
