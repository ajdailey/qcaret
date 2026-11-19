package io.github.ajdailey.qcaret.core.edit;

import io.github.ajdailey.qcaret.core.buffer.TextContent;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.document.EditBatch;
import io.github.ajdailey.qcaret.core.document.Replacement;
import io.github.ajdailey.qcaret.core.document.Selection;
import io.github.ajdailey.qcaret.core.document.SelectionSet;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;

/**
 * A {@link TextCodec} over each non-empty selection, as one {@link EditBatch.Kind#COMMAND} edit:
 * every selection converts or none does. A converted range stays selected over its new text; a
 * caret maps through the edit. Runs on a worker for a large text.
 */
public final class CodecEdits {
  private CodecEdits() {}

  /**
   * Reports whether a set holds a non-empty selection.
   *
   * @param set the selections
   * @return true when one is a range
   */
  public static boolean hasRange(SelectionSet set) {
    for (Selection s : set.selections()) {
      if (!s.isEmpty()) {
        return true;
      }
    }
    return false;
  }

  /**
   * Converts each non-empty selection.
   *
   * @param text the text
   * @param set the selections
   * @param codec the conversion
   * @param token checked through each conversion
   * @return the edit, or null when no selection changes
   * @throws CodecException naming the first refused selection; nothing is converted
   * @throws CancellationException when canceled
   */
  public static Edit convert(
      TextContent text, SelectionSet set, TextCodec codec, CancellationToken token)
      throws CodecException {
    List<Replacement> out = new ArrayList<Replacement>();
    int index = 0;
    for (Selection s : set.selections()) {
      if (s.isEmpty()) {
        continue;
      }
      index++;
      String old = text.subSequence(s.start(), s.end()).toString();
      String converted;
      try {
        converted = codec.convert(old, token);
      } catch (CodecException e) {
        throw new CodecException(e.reason(), index);
      }
      if (!converted.equals(old)) {
        out.add(new Replacement(s.start(), old.length(), converted));
      }
    }
    if (out.isEmpty()) {
      return null;
    }
    List<Selection> after = new ArrayList<Selection>(set.size());
    for (Selection s : set.selections()) {
      if (s.isEmpty()) {
        int at = Lines.map(out, s.caret(), true);
        after.add(new Selection(at, at, s.preferredColumn()));
      } else {
        int start = Lines.map(out, s.start(), true);
        int end = Lines.map(out, s.end(), false);
        after.add(s.anchor() <= s.caret() ? Selection.of(start, end) : Selection.of(end, start));
      }
    }
    return new Edit(
        EditBatch.of(EditBatch.Kind.COMMAND, out), SelectionSet.of(after, set.primaryIndex()));
  }
}
