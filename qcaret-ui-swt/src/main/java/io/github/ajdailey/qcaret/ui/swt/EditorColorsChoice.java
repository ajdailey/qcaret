package io.github.ajdailey.qcaret.ui.swt;

import io.github.ajdailey.qcaret.app.theme.EditorSchemes;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;

/**
 * The Preferences Editor colors list: the {@link EditorSchemes} in their order, then the theme
 * files a worker lists. A value is a scheme name or a theme file's name.
 */
final class EditorColorsChoice {
  private final Combo combo;
  private final List<String> values = new ArrayList<String>();

  EditorColorsChoice(Composite page) {
    combo = new Combo(page, SWT.DROP_DOWN | SWT.READ_ONLY);
    for (String scheme : EditorSchemes.names()) {
      combo.add(EditorSchemes.label(scheme));
      values.add(scheme);
    }
  }

  Combo combo() {
    return combo;
  }

  /**
   * Selects a value, adding a theme file the list lacks so that a missing file stays chosen.
   *
   * @param value a scheme or a theme file's name
   */
  void select(String value) {
    int index = values.indexOf(value);
    if (index < 0) {
      values.add(value);
      combo.add(value);
      index = values.size() - 1;
    }
    combo.select(index);
  }

  /**
   * Returns the selected value.
   *
   * @return a scheme or a theme file's name
   */
  String selected() {
    int index = combo.getSelectionIndex();
    return index < 0 ? EditorSchemes.FOLLOW : values.get(index);
  }

  /**
   * Replaces the theme files and keeps the selected value.
   *
   * @param files the theme files' names
   */
  void setFiles(List<String> files) {
    String keep = selected();
    int schemes = EditorSchemes.names().size();
    if (combo.getItemCount() > schemes) {
      combo.remove(schemes, combo.getItemCount() - 1);
      values.subList(schemes, values.size()).clear();
    }
    for (String file : files) {
      combo.add(file);
      values.add(file);
    }
    select(keep);
  }
}
