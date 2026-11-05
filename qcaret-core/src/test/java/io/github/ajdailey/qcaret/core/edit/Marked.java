package io.github.ajdailey.qcaret.core.edit;

import io.github.ajdailey.qcaret.core.document.Document;
import io.github.ajdailey.qcaret.core.document.Selection;
import io.github.ajdailey.qcaret.core.document.SelectionModel;
import io.github.ajdailey.qcaret.core.document.SelectionSet;
import io.github.ajdailey.qcaret.core.document.UndoLimits;
import io.github.ajdailey.qcaret.core.text.LineEnding;
import io.github.ajdailey.qcaret.core.text.TextEncoding;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Text with its selections marked: {@code |} is a caret, {@code [} and {@code ]} a range's anchor
 * and caret, {@code >} and {@code <} a range whose caret is before its anchor. The first marked
 * selection is primary unless one is marked with {@code *} right after it.
 */
final class Marked {
  final Document document;
  final SelectionModel model;

  Marked(String marked) {
    StringBuilder text = new StringBuilder();
    List<Selection> list = new ArrayList<Selection>();
    int primary = 0;
    int open = -1;
    boolean reversed = false;
    for (int i = 0; i < marked.length(); i++) {
      char c = marked.charAt(i);
      switch (c) {
        case '|' -> list.add(Selection.caret(text.length()));
        case '[' -> open = text.length();
        case ']' -> list.add(Selection.of(open, text.length()));
        case '>' -> {
          open = text.length();
          reversed = true;
        }
        case '<' -> {
          list.add(Selection.of(text.length(), open));
          reversed = false;
        }
        case '*' -> primary = list.size() - 1;
        default -> text.append(c);
      }
    }
    document =
        Document.of(
            text,
            TextEncoding.of(StandardCharsets.UTF_8, false),
            LineEnding.LF,
            UndoLimits.DEFAULTS);
    model = document.newSelectionModel();
    if (!list.isEmpty()) {
      model.set(SelectionSet.of(list, primary));
    }
    if (reversed) {
      throw new IllegalArgumentException("unclosed >");
    }
  }

  /** Applies an edit with the model as origin; null applies nothing. */
  Marked apply(Edit edit) {
    if (edit != null) {
      document.apply(edit.batch(), model, edit.after());
    }
    return this;
  }

  /** Returns the text with the model's selections marked, the primary followed by {@code *}. */
  String marked() {
    String text = document.text().toString();
    SelectionSet set = model.selections();
    StringBuilder out = new StringBuilder();
    List<Selection> list = set.selections();
    for (int i = 0; i <= text.length(); i++) {
      for (int k = 0; k < list.size(); k++) {
        Selection s = list.get(k);
        String star = k == set.primaryIndex() && k > 0 ? "*" : "";
        if (s.isEmpty() && s.caret() == i) {
          out.append('|').append(star);
        } else if (!s.isEmpty() && s.start() == i) {
          out.append(s.anchor() < s.caret() ? "[" : ">");
        } else if (!s.isEmpty() && s.end() == i) {
          out.append(s.anchor() < s.caret() ? "]" : "<").append(star);
        }
      }
      if (i < text.length()) {
        out.append(text.charAt(i));
      }
    }
    return out.toString();
  }
}
