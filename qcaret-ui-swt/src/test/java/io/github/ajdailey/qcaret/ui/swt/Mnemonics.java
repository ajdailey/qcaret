package io.github.ajdailey.qcaret.ui.swt;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;

/** The mnemonics of the labels, buttons and groups under a dialog's controls. */
final class Mnemonics {
  private Mnemonics() {}

  /**
   * Returns the mnemonic of a label text.
   *
   * @param text the text, with {@code &&} for a literal ampersand
   * @return the lowercase letter after the single {@code &}, or 0 for none
   */
  static char of(String text) {
    for (int i = 0; i < text.length() - 1; i++) {
      if (text.charAt(i) == '&') {
        if (text.charAt(i + 1) != '&') {
          return Character.toLowerCase(text.charAt(i + 1));
        }
        i++;
      }
    }
    return 0;
  }

  /**
   * Lists the mnemonics two or more controls share.
   *
   * @param roots the controls searched with their descendants
   * @param skip a control left out with its descendants, or null
   * @return each shared mnemonic with the texts that carry it; empty when every one is distinct
   */
  static List<String> clashes(List<Control> roots, Control skip) {
    Map<Character, List<String>> found = new LinkedHashMap<Character, List<String>>();
    for (Control root : roots) {
      collect(root, skip, found);
    }
    List<String> clashes = new ArrayList<String>();
    for (Map.Entry<Character, List<String>> entry : found.entrySet()) {
      if (entry.getValue().size() > 1) {
        clashes.add(entry.getKey() + ": " + entry.getValue());
      }
    }
    return clashes;
  }

  private static void collect(Control control, Control skip, Map<Character, List<String>> found) {
    if (control == skip) {
      return;
    }
    String text = null;
    if (control instanceof Button button) {
      text = button.getText();
    } else if (control instanceof Label label) {
      text = label.getText();
    } else if (control instanceof Group group) {
      text = group.getText();
    }
    char mnemonic = text == null ? 0 : of(text);
    if (mnemonic != 0) {
      List<String> texts = found.get(mnemonic);
      if (texts == null) {
        texts = new ArrayList<String>();
        found.put(mnemonic, texts);
      }
      texts.add(text);
    }
    if (control instanceof Composite composite) {
      for (Control child : composite.getChildren()) {
        collect(child, skip, found);
      }
    }
  }
}
