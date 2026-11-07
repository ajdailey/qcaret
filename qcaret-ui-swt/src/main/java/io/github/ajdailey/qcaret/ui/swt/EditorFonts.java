package io.github.ajdailey.qcaret.ui.swt;

import io.github.ajdailey.qcaret.core.text.LogText;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.widgets.Display;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolves {@code editor.fontFamily} to the family the views draw with. {@code ColumnMetrics} needs
 * a monospace font, so a saved family that is not installed, or that measures proportional, gives
 * way to {@link Dialogs#monospaceName} with one WARN; the saved value stays, and the family returns
 * once it is installed. Call on the UI thread.
 */
final class EditorFonts {
  private static final Logger LOG = LoggerFactory.getLogger(EditorFonts.class);
  private static final int MEASURE_POINTS = 12;
  private static final String NARROW = "iiiiiiiiiiiiiiiiiiii";
  private static final String WIDE = "WWWWWWWWWWWWWWWWWWWW";

  private EditorFonts() {}

  /**
   * Returns the family to draw with.
   *
   * @param display the Display
   * @param saved the saved family; empty for the platform's monospace font
   * @return {@code saved} when it can be used, else the platform's monospace font
   */
  static String family(Display display, String saved) {
    if (saved.isEmpty()) {
      return Dialogs.monospaceName();
    }
    if (!installed(display, saved)) {
      LOG.warn("Default font used: {} is not installed", LogText.oneLine(saved));
      return Dialogs.monospaceName();
    }
    if (!monospace(display, saved)) {
      LOG.warn("Default font used: {} is not monospace", LogText.oneLine(saved));
      return Dialogs.monospaceName();
    }
    return saved;
  }

  /**
   * Returns the given families the toolkit lists, from one pass over its scalable fonts. A font
   * file's family can be missing from that list: macOS keeps some system families out of it.
   */
  static List<String> listed(Display display, List<String> families) {
    Set<String> names = new TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
    for (FontData data : display.getFontList(null, true)) {
      names.add(data.getName());
    }
    List<String> out = new ArrayList<String>(families.size());
    for (String family : families) {
      if (names.contains(family)) {
        out.add(family);
      }
    }
    return out;
  }

  /** Reports whether the toolkit lists a family, scalable or not. */
  static boolean installed(Display display, String family) {
    return display.getFontList(family, true).length > 0
        || display.getFontList(family, false).length > 0;
  }

  /** Reports whether narrow and wide letters of a family measure the same. */
  static boolean monospace(Display display, String family) {
    Font font = new Font(display, family, MEASURE_POINTS, SWT.NORMAL);
    GC gc = new GC(display);
    try {
      gc.setFont(font);
      return Math.abs(gc.textExtent(NARROW).x - gc.textExtent(WIDE).x) <= 1;
    } finally {
      gc.dispose();
      font.dispose();
    }
  }
}
