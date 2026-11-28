package io.github.ajdailey.qcaret.ui.swt.platform;

import io.github.ajdailey.qcaret.app.theme.Palette;
import io.github.ajdailey.qcaret.app.theme.Rgb;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.SWT;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.PaletteData;
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
 * Composite}, {@code Label}, {@code Link}, {@link Text}, {@link Tree} and {@link Table} light.
 * While {@code ui.theme} resolves to dark, each control under a surface takes {@link #BACKGROUND}
 * and {@link #TEXT}, the neutral grays of {@link Win32DialogColors}, whatever the {@code
 * ui.editorColors} scheme; a {@link Link} takes its link color raised to 4.5 contrast, and each
 * composite passes its background to push button corners ({@code SWT.INHERIT_DEFAULT}). A {@link
 * Text}, a {@link Tree} and a {@link Table} take {@link #FIELD}, a tree's or table's header {@link
 * #BACKGROUND}, and a read-only {@link Combo} {@link #FACE}, since the dark native theme leaves its
 * drop-down list white without colors. A tree with columns also takes a {@link #FIELD} background
 * image under dark: win32 SWT fills a sorted column's cells and the area below its last row in the
 * background plus 8 per channel ({@code #333333} on {@link #FIELD}) unless the tree has a
 * background image. While it resolves to light every such color and image is null and composites
 * take {@code SWT.INHERIT_NONE}. Both directions apply live.
 *
 * <p>A check box is created under the native setting, so its box draws dark under the dark one; on
 * the light side under that setting its label takes the system widget text, since the dark native
 * box draws it near-white without colors.
 *
 * <p>Under the dark native setting ({@link DialogColors#nativeDark}) a field holding text keeps the
 * dark colors on the light side until its window is rebuilt, because the dark native field draws
 * near-black with dark text under any light colors; an empty field takes the system list colors,
 * set explicitly. {@link Win32EmptyField} paints an empty field in its background while unfocused,
 * on either side, with its hint in {@link Win32DialogColors#HINT} under dark, since the dark native
 * field draws {@code #212121} there under any colors. A field is colored again when it becomes
 * empty or takes text ({@code SWT.Modify}). A tree or table whose side differs from the native
 * start draws its selected row in the palette's selection colors ({@link #selection}) on {@link
 * SWT#EraseItem}; the native expander keeps the native setting's colors.
 *
 * <p>Push and arrow buttons, editable combos, lists, spinners, progress bars, a {@link StyledText},
 * a {@link Browser}, a {@link CTabFolder} and a control with {@link #PALETTE_COLORED} keep their
 * colors, and the walk does not descend into them. A control created later under a surface takes
 * the colors at its {@link SWT#Skin} event.
 *
 * <p>Each {@link #apply} colors the surfaces first and then re-inks every icon set, since the ink
 * is read from a colored tool bar; under dark a set's disabled items show faded copies. A checked
 * tool item draws the checked background of the native setting it was created under, light under
 * the light one, so a checked item takes the light ink only under the dark setting.
 */
final class Win32ChromeColors implements ChromeColors {
  /** Background of every surface under dark: {@link Win32DialogColors#WINDOW}. */
  static final Color BACKGROUND = Win32DialogColors.WINDOW;

  /** Text under dark: {@link Win32DialogColors#TEXT}. */
  static final Color TEXT = Win32DialogColors.TEXT;

  /** Field, tree and table background under dark: {@link Win32DialogColors#FIELD}. */
  static final Color FIELD = Win32DialogColors.FIELD;

  /** Read-only combo background under dark: {@link Win32DialogColors#FACE}. */
  static final Color FACE = Win32DialogColors.FACE;

  /** Control data key: set on a field once it follows {@code SWT.Modify}. */
  private static final String FOLLOWED = Win32ChromeColors.class.getName() + ".followed";

  /** Control data key: set on a tree once it draws its own selection. */
  private static final String SELECTION = Win32ChromeColors.class.getName() + ".selection";

  private final Display display;
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

  /**
   * {@link Win32EmptyField} fills an empty field with its background: under dark with the hint in
   * {@link Win32DialogColors#HINT}; on the light side under the dark native setting with the hint
   * in the system gray raised to {@link Contrast#LINK}.
   */
  private final Win32EmptyField.Ink ink =
      new Win32EmptyField.Ink() {
        @Override
        public Color hint(Text text) {
          if (dark) {
            return Win32DialogColors.HINT;
          }
          if (!nativeDark()) {
            return null;
          }
          RGB gray = display.getSystemColor(SWT.COLOR_WIDGET_DISABLED_FOREGROUND).getRGB();
          return new Color(Contrast.readable(gray, text.getBackground().getRGB()));
        }
      };

  private final List<Control> surfaces = new ArrayList<Control>();
  private final List<Icons> icons = new ArrayList<Icons>();
  private boolean dark;
  private boolean painted;
  private Boolean built;
  private Image ground;

  /**
   * Creates the colors for a window. The native setting ({@link DialogColors#nativeDark}) is read
   * at the first use and kept, so the theme service may record it after this, and a window waiting
   * for its rebuild keeps the setting its controls were created under.
   *
   * @param display the Display
   */
  Win32ChromeColors(Display display) {
    this.display = display;
    display.addListener(SWT.Skin, skin);
  }

  /** Returns the native setting at the first call, the one the window's controls take. */
  private boolean nativeDark() {
    if (built == null) {
      built = DialogColors.nativeDark(display);
    }
    return built;
  }

  @Override
  public void dispose() {
    if (!display.isDisposed()) {
      display.removeListener(SWT.Skin, skin);
    }
    if (ground != null) {
      ground.dispose();
      ground = null;
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
    set.lightChecked(nativeDark());
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
      set.lightChecked(nativeDark());
      set.fadeDisabled(value);
      set.reink();
    }
  }

  private void walk(Control control) {
    if (keeps(control)) {
      return;
    }
    if (control instanceof Combo) {
      control.setBackground(dark ? FACE : null);
      control.setForeground(dark ? TEXT : null);
      return;
    }
    if (control instanceof Text field) {
      colorField(field);
      return;
    }
    if (control instanceof Tree || control instanceof Table) {
      colorList((Composite) control);
      return;
    }
    control.setBackground(dark ? BACKGROUND : null);
    control.setForeground(dark ? TEXT : lightText(control));
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

  /** Colors a {@link Tree} or {@link Table} and its header, and draws its selection once. */
  private void colorList(Composite list) {
    list.setBackground(dark ? FIELD : null);
    list.setForeground(dark ? TEXT : null);
    Color header = dark ? BACKGROUND : null;
    Color headerText = dark ? TEXT : null;
    if (list instanceof Tree tree) {
      tree.setHeaderBackground(header);
      tree.setHeaderForeground(headerText);
      Image image = dark && tree.getColumnCount() > 0 ? ground() : null;
      if (tree.getBackgroundImage() != image) {
        tree.setBackgroundImage(image);
      }
    } else if (list instanceof Table table) {
      table.setHeaderBackground(header);
      table.setHeaderForeground(headerText);
    }
    Win32HeaderText.follow(list, dark);
    if (list.getData(SELECTION) != null) {
      return;
    }
    list.setData(SELECTION, Boolean.TRUE);
    list.addListener(
        SWT.EraseItem,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            if ((event.detail & SWT.SELECTED) != 0 && painted && dark != nativeDark()) {
              RGB[] colors = selection(dark);
              event.gc.setBackground(new Color(colors[0]));
              event.gc.fillRectangle(event.x, event.y, event.width, event.height);
              event.gc.setForeground(new Color(colors[1]));
              event.detail &= ~(SWT.SELECTED | SWT.HOT);
            }
          }
        });
    Win32UnfocusedSelection.draw(
        list,
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return dark && nativeDark();
          }
        });
  }

  /** Returns a one-pixel {@link #FIELD} image, created at the first call. */
  private Image ground() {
    if (ground == null) {
      PaletteData palette = new PaletteData(0xff0000, 0xff00, 0xff);
      ImageData data = new ImageData(1, 1, 24, palette);
      data.setPixel(0, 0, palette.getPixel(FIELD.getRGB()));
      ground = new Image(display, data);
    }
    return ground;
  }

  /**
   * Returns the selected row's background and text where the list's side differs from the native
   * start, whose selection colors the native tree keeps: black on {@code #333333} after a dark
   * start on the light side, 1.66:1, measured on Windows 11.
   *
   * @param dark true when {@code ui.theme} resolves to dark
   * @return the palette's selection background and its text raised to {@link Contrast#LINK}
   */
  static RGB[] selection(boolean dark) {
    Palette palette = dark ? Palette.DARK : Palette.LIGHT;
    RGB background = color(palette.selection()).getRGB();
    RGB text = Contrast.readable(color(palette.selectionText()).getRGB(), background);
    return new RGB[] {background, text};
  }

  private void colorField(final Text field) {
    boolean nativeDark = nativeDark();
    boolean darkField = dark || (nativeDark && field.getCharCount() > 0);
    // An empty field with no colors draws the dark native field under the dark setting.
    Color light = nativeDark ? display.getSystemColor(SWT.COLOR_LIST_BACKGROUND) : null;
    Color lightText = nativeDark ? display.getSystemColor(SWT.COLOR_LIST_FOREGROUND) : null;
    field.setBackground(darkField ? FIELD : light);
    field.setForeground(darkField ? TEXT : lightText);
    if (field.getData(FOLLOWED) != null) {
      return;
    }
    field.setData(FOLLOWED, Boolean.TRUE);
    Win32EmptyField.draw(field, ink);
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

  /**
   * Returns a control's text color on the light side: the system widget text for a check box or
   * radio button under the dark native setting, which draws its label near-white without colors;
   * otherwise null.
   */
  private Color lightText(Control control) {
    if (control instanceof Button && nativeDark()) {
      return display.getSystemColor(SWT.COLOR_WIDGET_FOREGROUND);
    }
    return null;
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
    if (control instanceof Combo) {
      return (control.getStyle() & SWT.READ_ONLY) == 0;
    }
    return control instanceof org.eclipse.swt.widgets.List
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
    return new Color(Contrast.readable(theme, BACKGROUND.getRGB()));
  }

  private static Color color(Rgb rgb) {
    return new Color(rgb.red(), rgb.green(), rgb.blue());
  }
}
