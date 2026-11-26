package io.github.ajdailey.qcaret.ui.swt.platform;

import org.eclipse.swt.SWT;
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
import org.eclipse.swt.widgets.List;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.ProgressBar;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Spinner;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.Tree;

/**
 * win32: under the dark native setting SWT draws a dialog's title bar, push buttons and read-only
 * combos dark and every other surface light. {@link #apply} gives the dialog body the title bar's
 * color, forced onto its children so push button corners match, and sets each control's colors:
 * fields, lists, tables and trees the field colors, read-only combos the push button face, since a
 * forced background otherwise draws their native text dark on dark. Push buttons keep their native
 * faces, a {@link ProgressBar} its bar color, and a control with {@link
 * ChromeColors#PALETTE_COLORED} the palette. Controls created after the shell shows take their
 * colors from {@link SWT#Skin}. Under the light native setting nothing changes.
 *
 * <p>Native grid lines and header dividers draw {@code #f0f0f0}, so {@link #gridLines} hides the
 * lines and {@link Win32GridLines} draws {@link #GRID} rules, and {@link #apply} sets the dividers
 * to {@link #GRID} through {@link Win32HeaderLines}.
 *
 * <p>A single-line {@link Text}'s hint draws {@code #6d6d6d} when read-only (SWT draws it in {@code
 * COLOR_GRAYTEXT}) and {@code #575757} when editable, 2.74:1 and 1.96:1 on {@link #FIELD} (measured
 * on {@code windows-2025}); {@link Win32EmptyField} draws it in {@link #HINT} over that.
 *
 * <p>The dark native theme draws a check box label near-white, or light gray when disabled, and
 * {@code setForeground} reaches only an enabled box. The theme applies to a control when it is
 * created, so under the dark native setting {@link #check} creates a box outside a colored dialog
 * with the theme off. Confined to the UI thread.
 */
final class Win32DialogColors implements DialogColors {
  static final Win32DialogColors INSTANCE = new Win32DialogColors();

  /** Dialog body: the dark title bar's color, measured on {@code windows-2025}. */
  static final Color WINDOW = new Color(0x20, 0x20, 0x20);

  /** Read-only combos: the dark push button face, measured. */
  static final Color FACE = new Color(0x33, 0x33, 0x33);

  /** Text fields, lists, tables, trees and table headers. */
  static final Color FIELD = new Color(0x2b, 0x2b, 0x2b);

  /** Text on every surface. */
  static final Color TEXT = new Color(0xff, 0xff, 0xff);

  /**
   * Table grid lines and header dividers: 1.37:1 on {@link #FIELD}; native lines on a light table
   * are 1.14:1.
   */
  static final Color GRID = new Color(0x40, 0x40, 0x40);

  /** Hint text of an empty single-line field: 5.0 contrast on {@link #FIELD}. */
  static final Color HINT = new Color(0x9a, 0x9a, 0x9a);

  private static final Win32EmptyField.Ink DIALOG_INK =
      new Win32EmptyField.Ink() {
        @Override
        public Color hint(Text text) {
          return HINT;
        }
      };

  /** Shell data key on a colored dialog. */
  private static final String COLORED = Win32DialogColors.class.getName() + ".colored";

  /** Display data key once the {@link SWT#Skin} listener is installed. */
  private static final String SKIN = Win32DialogColors.class.getName() + ".skin";

  private Win32DialogColors() {}

  @Override
  public void apply(Shell shell) {
    Display display = shell.getDisplay();
    headerLines(display);
    if (!DialogColors.nativeDark(display)) {
      return;
    }
    shell.setData(COLORED, Boolean.TRUE);
    shell.setBackgroundMode(SWT.INHERIT_FORCE);
    shell.setBackground(WINDOW);
    shell.setForeground(TEXT);
    shell.addListener(
        SWT.Show,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            if (event.widget == shell) {
              walk(shell);
            }
          }
        });
    if (display.getData(SKIN) == null) {
      display.setData(SKIN, Boolean.TRUE);
      display.addListener(
          SWT.Skin,
          new Listener() {
            @Override
            public void handleEvent(Event event) {
              if (event.widget instanceof Control control
                  && !(control instanceof Shell)
                  && !control.isDisposed()
                  && colored(control)) {
                style(control);
              }
            }
          });
    }
  }

  /**
   * Sets the header divider color for the current side: {@link #GRID} under dark; under light, the
   * native color once no colored dialog is open, since only a colored dialog's tables have header
   * colors, which make SWT draw the dividers.
   */
  private static void headerLines(Display display) {
    if (DialogColors.nativeDark(display)) {
      Win32HeaderLines.set(display, GRID);
      return;
    }
    for (Shell open : display.getShells()) {
      if (!open.isDisposed() && open.getData(COLORED) != null) {
        return;
      }
    }
    Win32HeaderLines.set(display, null);
  }

  @Override
  public boolean colored(Control control) {
    return control.getShell().getData(COLORED) != null;
  }

  @Override
  public Button check(Composite parent) {
    Display display = parent.getDisplay();
    boolean light = DialogColors.nativeDark(display) && !colored(parent);
    if (light) {
      display.setDarkThemePreferred(false);
    }
    try {
      return new Button(parent, SWT.CHECK);
    } finally {
      if (light) {
        display.setDarkThemePreferred(true);
      }
    }
  }

  @Override
  public void gridLines(Table table) {
    if (colored(table)) {
      Win32GridLines.draw(table, GRID);
    } else {
      table.setLinesVisible(true);
    }
  }

  @Override
  public Color background(Shell owner) {
    return DialogColors.nativeDark(owner.getDisplay()) ? WINDOW : owner.getBackground();
  }

  private static void walk(Control control) {
    if (control instanceof Composite composite) {
      for (Control child : composite.getChildren()) {
        if (!(child instanceof Shell)) {
          style(child);
          walk(child);
        }
      }
    }
  }

  /**
   * Gives one control its dialog colors.
   *
   * @param control a control of a colored dialog
   */
  static void style(Control control) {
    if (control.getData(ChromeColors.PALETTE_COLORED) != null) {
      return;
    }
    if (control instanceof Button button && (button.getStyle() & SWT.PUSH) != 0) {
      return;
    }
    if (control instanceof Combo combo && (combo.getStyle() & SWT.READ_ONLY) != 0) {
      control.setBackground(FACE);
    } else if (control instanceof Text
        || control instanceof Combo
        || control instanceof List
        || control instanceof Table
        || control instanceof Tree
        || control instanceof Spinner
        || control instanceof StyledText) {
      control.setBackground(FIELD);
    } else {
      control.setBackground(WINDOW);
    }
    if (!(control instanceof ProgressBar)) {
      control.setForeground(TEXT);
    }
    if (control instanceof Text text) {
      Win32EmptyField.draw(text, DIALOG_INK);
    }
    if (control instanceof Table table) {
      table.setHeaderBackground(FIELD);
      table.setHeaderForeground(TEXT);
    }
    if (control instanceof Link link) {
      RGB theme = link.getDisplay().getSystemColor(SWT.COLOR_LINK_FOREGROUND).getRGB();
      link.setLinkForeground(new Color(Contrast.readable(theme, WINDOW.getRGB())));
    }
  }
}
