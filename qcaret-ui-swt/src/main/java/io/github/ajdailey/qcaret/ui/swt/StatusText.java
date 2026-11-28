package io.github.ajdailey.qcaret.ui.swt;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;

/**
 * Sets the texts of a status bar's fields, labels in a {@link org.eclipse.swt.layout.GridLayout}. A
 * text that fits its field is set in place: no field moves or resizes. A counter field (the caret
 * position, the selection, the macro progress) keeps the widest width it has shown, so a shorter
 * count also fits; any other field fits only at its exact width. A text that does not fit sets
 * every text and lays the bar out under the bar's redraw hold, then paints the bar and its fields
 * before returning. A hidden label in the bar measures each text before it is set.
 *
 * <p>On win32 a label paints its new text as it is set, at its old size, and a moved field paints
 * after the move: a text set before the layout showed cut at the old width ("Ln 10178, Col 1"), or
 * beside old text the move uncovered ("Macro 57 of 20,00000"), for a frame. Confined to the UI
 * thread.
 */
final class StatusText {
  private final Composite bar;
  private final Label measure;
  private final Set<Label> counters = new HashSet<Label>();

  /**
   * Creates the measuring label in a status bar.
   *
   * @param bar the status bar
   */
  StatusText(Composite bar) {
    this.bar = bar;
    measure = new Label(bar, SWT.NONE);
    GridData data = new GridData();
    data.exclude = true;
    measure.setLayoutData(data);
    measure.setVisible(false);
  }

  /**
   * Makes a field a counter, which keeps the widest width it has shown.
   *
   * @param label the field, with {@link GridData}
   */
  void counter(Label label) {
    counters.add(label);
  }

  /**
   * Sets fields' texts; {@code &} shows as itself.
   *
   * @param labels the fields
   * @param texts their texts, in the same order
   * @return true when a text changed
   */
  boolean show(List<Label> labels, List<String> texts) {
    List<Label> changed = new ArrayList<Label>();
    List<String> shown = new ArrayList<String>();
    List<Integer> widths = new ArrayList<Integer>();
    boolean fits = true;
    for (int i = 0; i < labels.size(); i++) {
      Label label = labels.get(i);
      String text = texts.get(i).replace("&", "&&");
      if (label.getText().equals(text)) {
        continue;
      }
      int width = width(label, text);
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
      for (Label label : changed) {
        label.update();
      }
      return true;
    }
    bar.setRedraw(false);
    try {
      for (int i = 0; i < changed.size(); i++) {
        Label label = changed.get(i);
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
  void showOrHide(Label label, String text) {
    boolean visible = !text.isEmpty();
    if (visible == label.getVisible()) {
      show(List.of(label), List.of(text));
      return;
    }
    GridData data = (GridData) label.getLayoutData();
    bar.setRedraw(false);
    try {
      label.setText(text.replace("&", "&&"));
      label.setVisible(visible);
      data.exclude = !visible;
      data.widthHint = SWT.DEFAULT;
      bar.layout(true);
    } finally {
      bar.setRedraw(true);
    }
    paint();
  }

  private int width(Label label, String text) {
    if (measure.getFont() != label.getFont()) {
      measure.setFont(label.getFont());
    }
    measure.setText(text);
    return measure.computeSize(SWT.DEFAULT, SWT.DEFAULT, true).x;
  }

  /** Paints the bar and its fields now. */
  private void paint() {
    bar.update();
    for (Control child : bar.getChildren()) {
      child.update();
    }
  }
}
