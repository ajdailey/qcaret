package io.github.ajdailey.qcaret.ui.swt;

import io.github.ajdailey.qcaret.app.i18n.PseudoLocale;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CLabel;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Link;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.TabFolder;
import org.eclipse.swt.widgets.TabItem;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.swt.widgets.ToolItem;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeColumn;
import org.eclipse.swt.widgets.TreeItem;

/**
 * Reads the text a window shows under the {@code en-XA} pseudo locale and records each finding: an
 * unmarked text (a literal outside the bundle), a text joined from fragments, and a control
 * narrower than its text. A text matching an exemption is data, not UI text; a text matching an
 * unfitted exemption is checked but not for fit.
 */
final class PseudoTexts {
  /** Shown text by name, with the reason it is exempt and the whole text it matches. */
  record Exemption(String name, String reason, Pattern text) {}

  private final List<Exemption> exemptions;
  private final List<Exemption> unfitted;
  private final Set<String> findings = new LinkedHashSet<String>();

  PseudoTexts(List<Exemption> exemptions, List<Exemption> unfitted) {
    this.exemptions = exemptions;
    this.unfitted = unfitted;
  }

  /**
   * Returns the findings so far, one line each, without repeats.
   *
   * @return the findings
   */
  Set<String> findings() {
    return findings;
  }

  /**
   * Checks a shell: its title, every control under it, their menus, and the menu bar.
   *
   * @param where the shell's name in findings
   * @param shell the shell
   */
  void shell(String where, Shell shell) {
    text(where + " title", shell.getText());
    control(where, shell);
    if (shell.getMenuBar() != null) {
      menu(where + " menu", shell.getMenuBar());
    }
  }

  /**
   * Checks a control and its children; size is checked only where the control shows.
   *
   * @param where the window's name in findings
   * @param control the control
   */
  void control(String where, Control control) {
    String at = where + " " + control.getClass().getSimpleName();
    text(at + " tool tip", control.getToolTipText());
    if (control.getMenu() != null) {
      menu(at + " menu", control.getMenu());
    }
    if (control instanceof Label label && (label.getStyle() & SWT.SEPARATOR) == 0) {
      if (label.getImage() == null) {
        fitted(at, label, text(at, label.getText()), (label.getStyle() & SWT.WRAP) != 0);
      }
    } else if (control instanceof CLabel label) {
      fitted(at, label, text(at, label.getText()), false);
    } else if (control instanceof Button button) {
      fitted(at, button, text(at, button.getText()), (button.getStyle() & SWT.WRAP) != 0);
    } else if (control instanceof Link link) {
      String shown = link.getText().replaceAll("</?a[^>]*>", "");
      fitted(at, link, text(at, shown), false);
    } else if (control instanceof Group group) {
      text(at, group.getText());
    } else if (control instanceof Text field) {
      hint(at + " hint", field, text(at + " hint", field.getMessage()));
    } else if (control instanceof Combo combo && (combo.getStyle() & SWT.READ_ONLY) != 0) {
      for (String item : combo.getItems()) {
        text(at + " item", item);
      }
    } else if (control instanceof Table table) {
      table(at, table);
    } else if (control instanceof Tree tree) {
      for (TreeColumn column : tree.getColumns()) {
        text(at + " column", column.getText());
        text(at + " column tip", column.getToolTipText());
      }
      int columns = Math.max(1, tree.getColumnCount());
      for (TreeItem item : tree.getItems()) {
        for (int column = 0; column < columns; column++) {
          text(at + " row", item.getText(column));
        }
      }
    } else if (control instanceof ToolBar bar) {
      for (ToolItem item : bar.getItems()) {
        text(at + " item", item.getText());
        text(at + " item tip", item.getToolTipText());
      }
    } else if (control instanceof TabFolder folder) {
      for (TabItem item : folder.getItems()) {
        text(at + " tab", item.getText());
        text(at + " tab tip", item.getToolTipText());
      }
    } else if (control instanceof CTabFolder folder) {
      for (CTabItem item : folder.getItems()) {
        text(at + " tab", item.getText());
        text(at + " tab tip", item.getToolTipText());
      }
    }
    if (control instanceof Composite composite && !(control instanceof StyledText)) {
      for (Control child : composite.getChildren()) {
        control(where, child);
      }
    }
  }

  private void table(String at, Table table) {
    for (TableColumn column : table.getColumns()) {
      text(at + " column", column.getText());
      text(at + " column tip", column.getToolTipText());
    }
    int columns = Math.max(1, table.getColumnCount());
    for (TableItem item : table.getItems()) {
      for (int column = 0; column < columns; column++) {
        text(at + " row", item.getText(column));
      }
    }
  }

  /**
   * Checks a menu's items and cascades after firing its Show listeners, which fill menus built on
   * opening.
   *
   * @param where the menu's name in findings
   * @param menu the menu
   */
  void menu(String where, Menu menu) {
    menu.notifyListeners(SWT.Show, new Event());
    for (MenuItem item : menu.getItems()) {
      if ((item.getStyle() & SWT.SEPARATOR) != 0) {
        continue;
      }
      String shown = item.getText();
      int tab = shown.indexOf('\t');
      text(where + " item", tab < 0 ? shown : shown.substring(0, tab));
      text(where + " item tip", item.getToolTipText());
      if (item.getMenu() != null) {
        menu(where + " > " + plain(shown), item.getMenu());
      }
    }
    menu.notifyListeners(SWT.Hide, new Event());
  }

  /**
   * Checks one shown text and records a finding for it; each line of a text of several lines is
   * checked alone.
   *
   * @param at where it shows
   * @param shown the text, or null
   * @return the text, or null when it is empty or exempt
   */
  String text(String at, String shown) {
    if (shown == null || shown.isBlank()) {
      return null;
    }
    if (shown.indexOf('\n') >= 0) {
      boolean whole = true;
      for (String line : shown.split("\n")) {
        whole &= line.isBlank() || text(at + " line", line) != null;
      }
      return whole ? shown : null;
    }
    String plain = plain(shown).strip();
    for (Exemption exemption : exemptions) {
      if (exemption.text().matcher(plain).matches()) {
        return null;
      }
    }
    String fault = fault(plain);
    if (fault != null) {
      findings.add(at + ": " + fault + ": '" + shown + "'");
      return null;
    }
    return shown;
  }

  /**
   * Returns why a text is not one pseudo value, or null when it is: one bracketed value, optionally
   * followed by an ellipsis, with any brackets of formatted arguments nested inside.
   *
   * @param plain the text without mnemonic markers
   * @return {@code unmarked}, {@code concatenated} or null
   */
  static String fault(String plain) {
    String body = plain.endsWith("...") ? plain.substring(0, plain.length() - 3) : plain;
    if (body.indexOf(PseudoLocale.OPEN) < 0) {
      return "unmarked";
    }
    if (body.charAt(0) != PseudoLocale.OPEN) {
      return "concatenated";
    }
    int depth = 0;
    for (int i = 0; i < body.length(); i++) {
      char c = body.charAt(i);
      if (c == PseudoLocale.OPEN) {
        depth++;
      } else if (c == PseudoLocale.CLOSE) {
        depth--;
        if (depth == 0 && i != body.length() - 1) {
          return "concatenated";
        }
      }
    }
    return depth == 0 ? null : "concatenated";
  }

  /** The text without mnemonic markers; {@code &&} shows as one ampersand. */
  static String plain(String shown) {
    return shown.replace("&&", "\u0000").replace("&", "").replace('\u0000', '&');
  }

  /** Records a finding when a showing empty field is narrower than its hint. */
  private void hint(String at, Text field, String shown) {
    int width = field.getClientArea().width;
    if (shown == null || !field.isVisible() || width == 0 || !field.getText().isEmpty()) {
      return;
    }
    GC gc = new GC(field);
    try {
      gc.setFont(field.getFont());
      int needs = gc.textExtent(shown).x;
      if (needs > width) {
        findings.add(at + ": clipped, needs " + needs + ", has " + width + ": '" + shown + "'");
      }
    } finally {
      gc.dispose();
    }
  }

  /**
   * Records a finding when a showing control is narrower than its text or its preferred size, or a
   * wrapping control is shorter than its wrapped text. A control with no area does not show.
   */
  private void fitted(String at, Control control, String shown, boolean wraps) {
    Point size = control.getSize();
    if (shown == null || !control.isVisible() || size.x == 0 || size.y == 0) {
      return;
    }
    for (Exemption exemption : unfitted) {
      if (exemption.text().matcher(plain(shown)).matches()) {
        return;
      }
    }
    GC gc = new GC(control);
    int width;
    try {
      gc.setFont(control.getFont());
      width = gc.textExtent(shown, SWT.DRAW_MNEMONIC | SWT.DRAW_DELIMITER).x;
    } finally {
      gc.dispose();
    }
    if (wraps) {
      int height = control.computeSize(size.x, SWT.DEFAULT).y;
      if (height > size.y) {
        findings.add(
            at + ": clipped, needs height " + height + ", has " + size.y + ": '" + shown + "'");
      }
      return;
    }
    int preferred = Math.max(width, control.computeSize(SWT.DEFAULT, SWT.DEFAULT).x);
    if (preferred > size.x) {
      findings.add(at + ": clipped, needs " + preferred + ", has " + size.x + ": '" + shown + "'");
    }
  }
}
