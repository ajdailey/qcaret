package io.github.ajdailey.qcaret.ui.swt.editor;

import io.github.ajdailey.qcaret.app.editor.DisplayProjection;
import io.github.ajdailey.qcaret.app.editor.HighlightComposer;
import io.github.ajdailey.qcaret.app.editor.TokenSource;
import io.github.ajdailey.qcaret.app.theme.Palette;
import io.github.ajdailey.qcaret.app.theme.Rgb;
import io.github.ajdailey.qcaret.app.theme.TokenRole;
import io.github.ajdailey.qcaret.app.theme.TokenStyle;
import io.github.ajdailey.qcaret.core.document.Selection;
import io.github.ajdailey.qcaret.core.document.SelectionModel;
import io.github.ajdailey.qcaret.core.document.SelectionSet;
import java.util.Arrays;
import java.util.List;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.graphics.Color;

/**
 * An {@link EditorPane}'s line styles: a {@link HighlightComposer} over the syntax tokens, find
 * matches, bracket pair, word occurrences and secondary selections, its runs turned into {@link
 * StyleRange}s in the palette's token role and background colors.
 */
final class PaneHighlights {
  private final DisplayProjection projection;
  private final SelectionModel selections;
  private final HighlightComposer composer = new HighlightComposer();
  private Color selectionBack;
  private Color selectionFore;
  private Color findMatch;
  private Color[] roleColors = new Color[0];
  private int[] roleFontStyles = new int[0];
  private boolean[] roleUnderlines = new boolean[0];
  private Color occurrenceBack;
  private Color bracketBack;

  PaneHighlights(DisplayProjection projection, SelectionModel selections) {
    this.projection = projection;
    this.selections = selections;
  }

  /** Takes the selection, find match, bracket, occurrence and token role colors of a palette. */
  void setPalette(Palette palette) {
    selectionBack = color(palette.selection());
    selectionFore = color(palette.selectionText());
    findMatch = color(palette.findMatch());
    TokenRole[] roles = TokenRole.values();
    roleColors = new Color[roles.length];
    roleFontStyles = new int[roles.length];
    roleUnderlines = new boolean[roles.length];
    for (TokenRole role : roles) {
      TokenStyle style = palette.style(role);
      roleColors[role.ordinal()] = color(style.foreground());
      roleFontStyles[role.ordinal()] =
          (style.bold() ? SWT.BOLD : SWT.NORMAL) | (style.italic() ? SWT.ITALIC : SWT.NORMAL);
      roleUnderlines[role.ordinal()] = style.underline();
    }
    occurrenceBack = color(palette.occurrence());
    bracketBack = color(palette.bracket());
  }

  private static Color color(Rgb rgb) {
    return new Color(rgb.red(), rgb.green(), rgb.blue());
  }

  void setMatches(int[] ranges) {
    composer.setMatches(ranges);
  }

  int[] matches() {
    return composer.matches();
  }

  void setSyntax(TokenSource tokens) {
    composer.setSyntax(tokens);
  }

  void setBrackets(int[] ranges) {
    composer.setBrackets(ranges);
  }

  int[] brackets() {
    return composer.brackets();
  }

  void setOccurrences(int[] ranges) {
    composer.setOccurrences(ranges);
  }

  int[] occurrences() {
    return composer.occurrences();
  }

  /** Returns a row's styles from the composer's runs. */
  StyleRange[] styles(int rowOffset, int rowLength) {
    int from = projection.toModel(rowOffset);
    int line = projection.lineOfRow(projection.rowAt(rowOffset));
    int lineStart = projection.lineStart(line);
    int[] runs =
        composer.compose(line, lineStart, from, from + rowLength, secondaryRanges(from, rowLength));
    StyleRange[] styles = new StyleRange[runs.length / 4];
    int selection = HighlightComposer.Background.SELECTION.ordinal();
    Color[] backgrounds = {selectionBack, bracketBack, findMatch, occurrenceBack};
    for (int i = 0; i < runs.length; i += 4) {
      int role = runs[i + 2];
      int background = runs[i + 3];
      StyleRange style = new StyleRange();
      style.start = rowOffset + runs[i];
      style.length = runs[i + 1];
      if (role != HighlightComposer.NONE) {
        style.foreground = roleColors[role];
        style.fontStyle = roleFontStyles[role];
        style.underline = roleUnderlines[role];
      }
      if (background == selection) {
        style.foreground = selectionFore;
        style.background = selectionBack;
      } else if (background != HighlightComposer.NONE) {
        style.background = backgrounds[background];
      }
      styles[i / 4] = style;
    }
    return styles;
  }

  /** Returns the secondary selections within one row as model offset pairs. */
  private int[] secondaryRanges(int from, int rowLength) {
    SelectionSet set = selections.selections();
    if (set.size() == 1) {
      return new int[0];
    }
    int to = from + rowLength;
    int[] out = new int[16];
    int size = 0;
    List<Selection> list = set.selections();
    for (int i = firstEndingAtOrAfter(list, from); i < list.size(); i++) {
      Selection s = list.get(i);
      if (s.start() >= to) {
        break;
      }
      if (i == set.primaryIndex() || s.isEmpty()) {
        continue;
      }
      int start = Math.max(s.start(), from);
      int end = Math.min(s.end(), to);
      if (end > start) {
        if (size + 2 > out.length) {
          out = Arrays.copyOf(out, out.length * 2);
        }
        out[size++] = start;
        out[size++] = end;
      }
    }
    return Arrays.copyOf(out, size);
  }

  /** Returns the index of the first selection whose end is at or after {@code offset}. */
  static int firstEndingAtOrAfter(List<Selection> list, int offset) {
    int lo = 0;
    int hi = list.size();
    while (lo < hi) {
      int mid = (lo + hi) >>> 1;
      if (list.get(mid).end() < offset) {
        lo = mid + 1;
      } else {
        hi = mid;
      }
    }
    return lo;
  }
}
