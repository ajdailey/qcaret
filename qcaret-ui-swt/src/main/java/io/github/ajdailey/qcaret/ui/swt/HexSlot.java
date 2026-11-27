package io.github.ajdailey.qcaret.ui.swt;

import io.github.ajdailey.qcaret.app.view.HexMode;
import io.github.ajdailey.qcaret.ui.swt.hex.HexPane;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Sash;

/**
 * A tab's hex pane in its frame, the {@link SashForm} that also holds the editors and the {@link
 * PreviewSlot}'s pane. Text hides the pane, Split shows it right of the editors at a row's width
 * while that leaves them a third of the frame, Hex maximizes it and hides the editors, since GTK 4
 * paints the children of a control the frame moves out of view. While the pane shows, the frame's
 * other panes are hidden and shown again on Text; the controller sets the Markdown mode to Source
 * first, so the preview never shows beside the pane. Leaving Hex with the pane focused focuses the
 * editor. Call on the UI thread.
 */
final class HexSlot {
  private final SashForm frame;
  private final Composite editors;
  private final HexPane pane;
  private final Runnable focusEditor;
  private final List<Control> hidden = new ArrayList<Control>();
  private HexMode mode = HexMode.TEXT;
  private boolean weighted;

  HexSlot(SashForm frame, Composite editors, HexPane pane, Runnable focusEditor) {
    this.frame = frame;
    this.editors = editors;
    this.pane = pane;
    this.focusEditor = focusEditor;
    pane.control().setVisible(false);
  }

  HexPane pane() {
    return pane;
  }

  HexMode mode() {
    return mode;
  }

  /** Shows the editors and the pane for a mode. */
  void showMode(HexMode next) {
    if (frame.isDisposed()) {
      return;
    }
    boolean paneFocused = hasFocus(pane.control());
    mode = next;
    Control shown = pane.control();
    switch (next) {
      case TEXT -> {
        shown.setVisible(false);
        for (Control control : hidden) {
          if (!control.isDisposed()) {
            control.setVisible(true);
          }
        }
        hidden.clear();
        editors.setVisible(true);
        if (frame.getMaximizedControl() == shown) {
          frame.setMaximizedControl(editors);
        }
      }
      case SPLIT -> {
        hideOthers();
        editors.setVisible(true);
        shown.setVisible(true);
        frame.setMaximizedControl(null);
        if (!weighted) {
          weighted = true;
          frame.setWeights(weights());
        }
      }
      case HEX -> {
        hideOthers();
        shown.setVisible(true);
        editors.setVisible(false);
        frame.setMaximizedControl(shown);
      }
    }
    frame.layout(true, true);
    if (paneFocused && next != HexMode.HEX) {
      focusEditor.run();
    }
  }

  private void hideOthers() {
    for (Control child : frame.getChildren()) {
      if (child != editors && child != pane.control() && !(child instanceof Sash)) {
        if (child.getVisible()) {
          child.setVisible(false);
          hidden.add(child);
        }
      }
    }
  }

  /** The pane at a row's width while the editors keep a third of the frame, else half each. */
  private int[] weights() {
    int[] all = frame.getWeights();
    int width = frame.getClientArea().width;
    int wanted = pane.preferredWidth();
    int at = 0;
    int paneAt = -1;
    for (Control child : frame.getChildren()) {
      if (child instanceof Sash) {
        continue;
      }
      if (child == pane.control()) {
        paneAt = at;
      }
      at++;
    }
    Arrays.fill(all, 1);
    if (paneAt > 0 && paneAt < all.length && width - wanted >= width / 3) {
      all[0] = width - wanted;
      all[paneAt] = wanted;
    } else if (paneAt > 0 && paneAt < all.length) {
      all[0] = 1000;
      all[paneAt] = 1000;
    }
    return all;
  }

  private static boolean hasFocus(Control control) {
    Control focus = control.getDisplay().getFocusControl();
    while (focus != null) {
      if (focus == control) {
        return true;
      }
      focus = focus.getParent();
    }
    return false;
  }
}
