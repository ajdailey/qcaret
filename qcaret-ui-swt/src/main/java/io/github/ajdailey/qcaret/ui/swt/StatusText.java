package io.github.ajdailey.qcaret.ui.swt;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;

/**
 * Sets the texts of a status bar's {@link StatusField}s, laid out in a {@link
 * org.eclipse.swt.layout.GridLayout}. A text that fits its field is set in place: no field moves or
 * resizes. A counter field (the caret position, the selection, the macro progress) keeps the widest
 * width it has shown, so a shorter count also fits; any other field fits only at its exact width. A
 * text that does not fit sets every text and lays the bar out under the bar's redraw hold, then
 * paints the bar and its fields before returning.
 *
 * <p>On win32 a field moved or resized outside the hold shows its old pixels at the new place until
 * it paints: text cut at the old width ("Ln 10178, Col 1"), or beside old text the move uncovered
 * ("Macro 57 of 20,00000"), for a frame. Confined to the UI thread.
 */
final class StatusText {
  private final Composite bar;
  private final Set<StatusField> counters = new HashSet<StatusField>();

  /**
   * Creates the texts' setter.
   *
   * @param bar the status bar
   */
  StatusText(Composite bar) {
    this.bar = bar;
  }

  /**
   * Makes a field a counter, which keeps the widest width it has shown.
   *
   * @param label the field, with {@link GridData}
   */
  void counter(StatusField label) {
    counters.add(label);
  }

  /**
   * Sets fields' texts.
   *
   * @param labels the fields
   * @param texts their texts, in the same order
   * @return true when a text changed
   */
  boolean show(List<StatusField> labels, List<String> texts) {
    List<StatusField> changed = new ArrayList<StatusField>();
    List<String> shown = new ArrayList<String>();
    List<Integer> widths = new ArrayList<Integer>();
    boolean fits = true;
    for (int i = 0; i < labels.size(); i++) {
      StatusField label = labels.get(i);
      String text = texts.get(i);
      if (label.getText().equals(text)) {
        continue;
      }
      int width = label.textSize(text).x;
      int now = label.getSize().x;
      fits &= counters.contains(label) ? width <= now : width == now;
      changed.add(label);
      shown.add(text);
      widths.add(width);
    }
    if (changed.isEmpty()) {
      return false;
    }
    if (fits) {
      for (int i = 0; i < changed.size(); i++) {
        changed.get(i).setText(shown.get(i));
      }
      for (StatusField label : changed) {
        label.update();
      }
      return true;
    }
    bar.setRedraw(false);
    try {
      for (int i = 0; i < changed.size(); i++) {
        StatusField label = changed.get(i);
        label.setText(shown.get(i));
        if (counters.contains(label) && label.getLayoutData() instanceof GridData data) {
          data.widthHint = Math.max(data.widthHint, Math.max(widths.get(i), label.getSize().x));
        }
      }
      bar.layout(true);
    } finally {
      bar.setRedraw(true);
    }
    paint();
    return true;
  }

  /**
   * Shows or hides a field and lays the bar out. A hidden counter is left out of the layout and
   * returns to its text's width when it shows again.
   *
   * @param label the field, with {@link GridData}
   * @param text its text; empty hides it
   */
  void showOrHide(StatusField label, String text) {
    boolean visible = !text.isEmpty();
    if (visible == label.getVisible()) {
      show(List.of(label), List.of(text));
      return;
    }
    GridData data = (GridData) label.getLayoutData();
    bar.setRedraw(false);
    try {
      label.setText(text);
      label.setVisible(visible);
      data.exclude = !visible;
      data.widthHint = SWT.DEFAULT;
      bar.layout(true);
    } finally {
      bar.setRedraw(true);
    }
    paint();
  }

  /** Paints the bar and its fields now. */
  private void paint() {
    bar.update();
    for (Control child : bar.getChildren()) {
      child.update();
    }
  }
}
