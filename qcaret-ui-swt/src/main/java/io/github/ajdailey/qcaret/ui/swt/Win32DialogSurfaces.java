package io.github.ajdailey.qcaret.ui.swt;

import io.github.ajdailey.qcaret.ui.swt.editor.EditorPane;
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
 * win32: under a dark startup setting SWT draws a dialog's title bar, push buttons and read-only
 * combos dark and every other surface light. {@link #apply} gives the dialog body the title bar's
 * color, forced onto its children so push button corners match, and sets each control's colors:
 * fields, lists, tables and trees the field colors, read-only combos the push button face, since a
 * forced background otherwise draws their native text dark on dark. Push buttons keep their native
 * faces, a {@link ProgressBar} its bar color, and a control with {@link EditorPane#PALETTE_COLORED}
 * the palette. Controls created after the shell shows take their colors from {@link SWT#Skin}.
 * Under a light startup setting nothing changes. Confined to the UI thread.
 */
final class Win32DialogSurfaces implements DialogSurfaces {
  static final Win32DialogSurfaces INSTANCE = new Win32DialogSurfaces();

  /** Dialog body: the dark title bar's color, measured on {@code windows-2025}. */
  static final Color WINDOW = new Color(0x20, 0x20, 0x20);

  /** Read-only combos: the dark push button face, measured. */
  static final Color FACE = new Color(0x33, 0x33, 0x33);

  /** Text fields, lists, tables, trees and table headers. */
  static final Color FIELD = new Color(0x2b, 0x2b, 0x2b);

  /** Text on every surface. */
  static final Color TEXT = new Color(0xff, 0xff, 0xff);

  /** Shell data key on a colored dialog. */
  private static final String COLORED = Win32DialogSurfaces.class.getName() + ".colored";

  /** Display data key once the {@link SWT#Skin} listener is installed. */
  private static final String SKIN = Win32DialogSurfaces.class.getName() + ".skin";

  private Win32DialogSurfaces() {}

  @Override
  public void apply(Shell shell) {
    Display display = shell.getDisplay();
    if (!SwtThemeService.nativeDark(display)) {
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

  @Override
  public boolean colored(Control control) {
    return control.getShell().getData(COLORED) != null;
  }

  @Override
  public Color background(Shell owner) {
    return SwtThemeService.nativeDark(owner.getDisplay()) ? WINDOW : owner.getBackground();
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
    if (control.getData(EditorPane.PALETTE_COLORED) != null) {
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
    if (control instanceof Table table) {
      table.setHeaderBackground(FIELD);
      table.setHeaderForeground(TEXT);
    }
    if (control instanceof Link link) {
      RGB theme = link.getDisplay().getSystemColor(SWT.COLOR_LINK_FOREGROUND).getRGB();
      link.setLinkForeground(new Color(SwtThemeService.readable(theme, WINDOW.getRGB())));
    }
  }
}
