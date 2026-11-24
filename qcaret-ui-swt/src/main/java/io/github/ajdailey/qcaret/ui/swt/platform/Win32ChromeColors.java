package io.github.ajdailey.qcaret.ui.swt.platform;

import io.github.ajdailey.qcaret.app.theme.Palette;
import io.github.ajdailey.qcaret.app.theme.Rgb;
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
 * The main window's surfaces on win32, where the dark native theme leaves a {@code ToolBar}, {@code
 * Composite}, {@code Label}, {@code Link}, {@link Text} and {@link Tree} light. While {@code
 * ui.theme} resolves to dark, each control under a surface takes {@link Palette#DARK}'s gutter, the
 * tab strip's background, and its text; a {@link Link} takes its link color raised to 4.5 contrast,
 * and each composite passes its background to push button corners ({@code SWT.INHERIT_DEFAULT}). A
 * {@link Text} and a {@link Tree} take {@link Palette#DARK}'s background and text, a tree's header
 * the gutter, and a field's {@link ChromeColors.Hint} the palette's unselected tab label color,
 * 5.9:1 on the field. While it resolves to light every such color is null and composites take
 * {@code SWT.INHERIT_NONE}. Both directions apply live.
 *
 * <p>After a dark start ({@link ChromeColors#nativeDark}) a field holding text keeps the dark
 * colors on the light side until the next start, because the dark native field draws near-black
 * with dark text under any light colors; an empty field takes the system colors. A field is colored
 * again when it becomes empty or takes text ({@code SWT.Modify}).
 *
 * <p>Push and arrow buttons, combos, lists, tables, spinners, progress bars, a {@link StyledText},
 * a {@link Browser}, a {@link CTabFolder} and a control with {@link #PALETTE_COLORED} keep their
 * colors, and the walk does not descend into them. A control created later under a surface takes
 * the colors at its {@link SWT#Skin} event.
 *
 * <p>Each {@link #apply} colors the surfaces first and then re-inks every icon set, since the ink
 * is read from a colored tool bar; under dark a set's disabled items show faded copies. A checked
 * tool item draws the checked background of the startup native theme, light after a light start, so
 * a checked item takes the light ink only after a dark start.
 */
final class Win32ChromeColors implements ChromeColors {
  /** Background of every surface under dark. */
  static final Rgb BACKGROUND = Palette.DARK.gutter();

  /** Text under dark. */
  static final Rgb TEXT = Palette.DARK.text();

  /** Field and tree background under dark. */
  static final Rgb FIELD = Palette.DARK.background();

  /** Field hint under dark. */
  static final Rgb HINT_TEXT = Palette.DARK.accents().tabText();

  /** Control data key: set on a field once it follows {@code SWT.Modify}. */
  private static final String FOLLOWED = Win32ChromeColors.class.getName() + ".followed";

  private final Display display;
  private final boolean nativeDark;
  private final Listener skin =
      new Listener() {
        @Override
        public void handleEvent(Event event) {
          if (painted
              && event.widget instanceof Control control
              && !control.isDisposed()
              && under(control)) {
            walk(control);
          }
        }
      };
  private final List<Control> surfaces = new ArrayList<Control>();
  private final List<Icons> icons = new ArrayList<Icons>();
  private boolean dark;
  private boolean painted;

  /**
   * Creates the colors for a window; call after the theme service records the native start.
   *
   * @param display the Display
   */
  Win32ChromeColors(Display display) {
    this.display = display;
    this.nativeDark = ChromeColors.nativeDark(display);
    display.addListener(SWT.Skin, skin);
  }

  @Override
  public void dispose() {
    if (!display.isDisposed()) {
      display.removeListener(SWT.Skin, skin);
    }
  }

  @Override
  public void add(final Control surface) {
    if (surfaces.contains(surface)) {
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
    if (painted) {
      walk(surface);
    }
  }

  @Override
  public void add(Icons set) {
    icons.add(set);
    set.lightChecked(nativeDark);
    set.fadeDisabled(dark);
  }

  @Override
  public void apply(boolean value) {
    if (!value && !painted) {
      return;
    }
    dark = value;
    painted = true;
    for (Control surface : new ArrayList<Control>(surfaces)) {
      if (!surface.isDisposed()) {
        walk(surface);
      }
    }
    for (Icons set : icons) {
      set.fadeDisabled(value);
      set.reink();
    }
  }

  private void walk(Control control) {
    if (keeps(control)) {
      return;
    }
    if (control instanceof Text field) {
      colorField(field);
      return;
    }
    if (control instanceof Tree tree) {
      colorTree(tree);
      return;
    }
    control.setBackground(dark ? color(BACKGROUND) : null);
    control.setForeground(dark ? color(TEXT) : null);
    if (control instanceof Link link) {
      link.setLinkForeground(dark ? linkColor(display) : null);
    }
    if (control instanceof Composite composite) {
      composite.setBackgroundMode(dark ? SWT.INHERIT_DEFAULT : SWT.INHERIT_NONE);
      for (Control child : composite.getChildren()) {
        walk(child);
      }
    }
  }

  private void colorTree(Tree tree) {
    tree.setBackground(dark ? color(FIELD) : null);
    tree.setForeground(dark ? color(TEXT) : null);
    tree.setHeaderBackground(dark ? color(BACKGROUND) : null);
    tree.setHeaderForeground(dark ? color(TEXT) : null);
  }

  private void colorField(final Text field) {
    boolean darkField = dark || (nativeDark && field.getCharCount() > 0);
    field.setBackground(darkField ? color(FIELD) : null);
    field.setForeground(darkField ? color(TEXT) : null);
    if (field.getData(HINT) instanceof Hint hint) {
      hint.setColor(darkField ? color(HINT_TEXT) : null);
    }
    if (field.getData(FOLLOWED) != null) {
      return;
    }
    field.setData(FOLLOWED, Boolean.TRUE);
    field.addListener(
        SWT.Modify,
        new Listener() {
          private boolean empty = field.getCharCount() == 0;

          @Override
          public void handleEvent(Event event) {
            boolean now = field.getCharCount() == 0;
            if (now != empty) {
              empty = now;
              colorField(field);
            }
          }
        });
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

  /** Reports whether a control keeps its own colors. */
  private static boolean keeps(Control control) {
    if (control.getData(PALETTE_COLORED) != null) {
      return true;
    }
    if (control instanceof Button) {
      return (control.getStyle() & (SWT.CHECK | SWT.RADIO)) == 0;
    }
    return control instanceof Combo
        || control instanceof org.eclipse.swt.widgets.List
        || control instanceof Table
        || control instanceof Spinner
        || control instanceof ProgressBar
        || control instanceof StyledText
        || control instanceof Browser
        || control instanceof CTabFolder;
  }

  /**
   * Returns the system link color raised to {@link Contrast#LINK} on {@link #BACKGROUND}.
   *
   * @param display the Display
   * @return the color
   */
  static Color linkColor(Display display) {
    RGB theme = display.getSystemColor(SWT.COLOR_LINK_FOREGROUND).getRGB();
    return new Color(Contrast.readable(theme, color(BACKGROUND).getRGB()));
  }

  private static Color color(Rgb rgb) {
    return new Color(rgb.red(), rgb.green(), rgb.blue());
  }
}
