package io.github.ajdailey.qcaret.app.editor;

import io.github.ajdailey.qcaret.core.buffer.ChunkVisitor;
import io.github.ajdailey.qcaret.core.buffer.TextSnapshot;
import io.github.ajdailey.qcaret.core.document.Anchor;
import io.github.ajdailey.qcaret.core.document.Bias;
import io.github.ajdailey.qcaret.core.document.Document;
import io.github.ajdailey.qcaret.core.document.TextEdit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * The bookmarks edits removed, kept for {@link Bookmarks} to restore when an undo or redo puts the
 * removed text back. One record per edit: anchors around the text the edit inserted, the removed
 * text's length and hash, and each bookmark's offset in the removed text with its comment. Holds
 * the newest {@link Bookmarks#MAX_REMOVED} records. Confined to the document's thread.
 */
final class RemovedBookmarks {
  /** One edit's record. */
  private static final class Removed {
    final Anchor start;
    final Anchor end;
    final int length;
    final int hash;
    final List<int[]> offsets = new ArrayList<int[]>();
    final List<String> comments = new ArrayList<String>();

    Removed(Anchor start, Anchor end, String text) {
      this.start = start;
      this.end = end;
      this.length = text.length();
      this.hash = text.hashCode();
    }

    void dispose() {
      start.dispose();
      end.dispose();
    }
  }

  private final Document document;
  private final Deque<Removed> removed = new ArrayDeque<Removed>();

  /** The current change's record per edit, null until the edit removes a bookmark. */
  private Removed[] groups = new Removed[0];

  /** The current change's records, in the order they were made. */
  private final List<Removed> added = new ArrayList<Removed>();

  /**
   * Creates the records with none kept.
   *
   * @param document the document whose edits remove the bookmarks
   */
  RemovedBookmarks(Document document) {
    this.document = document;
  }

  /** Reports whether no record is kept. */
  boolean isEmpty() {
    return removed.isEmpty();
  }

  /**
   * Starts the records of one change; {@link #endChange} keeps them.
   *
   * @param edits the change's edit count
   */
  void startChange(int edits) {
    groups = new Removed[edits];
    added.clear();
  }

  /**
   * Adds a removed bookmark to the record of the edit that removed it.
   *
   * @param edit the edit
   * @param at the edit's index in the change
   * @param shift the length change of the edits before it
   * @param before the bookmark's offset before the change
   * @param comment the bookmark's comment, or null
   */
  void keep(TextEdit edit, int at, int shift, int before, String comment) {
    Removed group = groups[at];
    if (group == null) {
      int start = edit.offset() + shift;
      group =
          new Removed(
              document.anchor(start, Bias.BEFORE),
              document.anchor(start + edit.inserted().length(), Bias.AFTER),
              edit.removed());
      groups[at] = group;
      added.add(group);
    }
    group.offsets.add(new int[] {before - edit.offset()});
    group.comments.add(comment);
  }

  /** Keeps the change's records, disposing of the oldest past {@link Bookmarks#MAX_REMOVED}. */
  void endChange() {
    for (Removed r : added) {
      removed.addLast(r);
      while (removed.size() > Bookmarks.MAX_REMOVED) {
        Removed oldest = removed.removeFirst();
        oldest.dispose();
      }
    }
    added.clear();
    groups = new Removed[0];
  }

  /**
   * Restores the bookmarks of each record whose removed text an undo or redo put back, at the start
   * or the end of the text between its anchors, and drops those records.
   *
   * @param text the document's text
   * @param bookmarks restores each bookmark
   * @return true when any record was restored
   */
  boolean restore(TextSnapshot text, Bookmarks bookmarks) {
    boolean changed = false;
    List<Removed> done = new ArrayList<Removed>();
    for (Removed r : removed) {
      int start = r.start.offset();
      int span = r.end.offset() - start;
      if (span < r.length) {
        continue;
      }
      int at = -1;
      if (hash(text, start, r.length) == r.hash) {
        at = start;
      } else if (span > r.length && hash(text, start + span - r.length, r.length) == r.hash) {
        at = start + span - r.length;
      }
      if (at >= 0) {
        for (int i = 0; i < r.offsets.size(); i++) {
          bookmarks.restore(text, at + r.offsets.get(i)[0], r.comments.get(i));
        }
        done.add(r);
        changed = true;
      }
    }
    for (Removed r : done) {
      removed.remove(r);
      r.dispose();
    }
    return changed;
  }

  /** Disposes of every record. */
  void clear() {
    for (Removed r : removed) {
      r.dispose();
    }
    removed.clear();
  }

  /** {@link String#hashCode} of a range of the text. */
  private static int hash(TextSnapshot text, int start, int length) {
    final int[] h = new int[1];
    text.readChunks(
        start,
        start + length,
        new ChunkVisitor() {
          @Override
          public void chunk(CharSequence chunk, int from, int to) {
            int value = h[0];
            for (int i = from; i < to; i++) {
              value = 31 * value + chunk.charAt(i);
            }
            h[0] = value;
          }
        });
    return h[0];
  }
}
