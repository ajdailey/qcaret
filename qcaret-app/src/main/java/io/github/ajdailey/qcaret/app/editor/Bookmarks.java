package io.github.ajdailey.qcaret.app.editor;

import io.github.ajdailey.qcaret.core.buffer.TextSnapshot;
import io.github.ajdailey.qcaret.core.document.Anchor;
import io.github.ajdailey.qcaret.core.document.Bias;
import io.github.ajdailey.qcaret.core.document.Document;
import io.github.ajdailey.qcaret.core.document.DocumentChange;
import io.github.ajdailey.qcaret.core.document.DocumentListener;
import io.github.ajdailey.qcaret.core.document.TextEdit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The bookmarked lines of one document and their comments, shared by its views. Each bookmark is an
 * {@link Anchor} at its line's start with {@link Bias#AFTER}, so a line break inserted at that
 * start moves it down with the line's text, and it marks whatever line holds the anchor. A comment
 * belongs to its bookmark and goes with it. Confined to the document's thread.
 *
 * <p>An edit that replaces a bookmarked line with inserted text holding the same line text moves
 * the bookmark to that text, so moved and sorted lines keep their bookmarks. Otherwise an edit
 * removes a bookmark when it removes the line: all of its text and its break, or, for the last
 * line, its text and the break before it. When an edit joins two bookmarked lines, the upper
 * bookmark stays and the lower one is removed. A removed bookmark is kept with the removed text's
 * length and hash; an undo or redo that puts the same text back at the same place restores it with
 * its comment. The newest {@value #MAX_REMOVED} such edits are kept; toggle, clear and remove are
 * not undoable.
 */
public final class Bookmarks implements DocumentListener {
  /** Most comments per document. */
  public static final int MAX_COMMENTS = 1000;

  /** Most chars of a comment. */
  public static final int MAX_COMMENT_LENGTH = 1000;

  /** Edits whose removed bookmarks an undo or redo can restore. */
  static final int MAX_REMOVED = 1000;

  /** What {@link #setComment} did. */
  public enum Result {
    /** The comment was set or removed. */
    DONE,
    /** Refused: longer than {@link #MAX_COMMENT_LENGTH} chars. */
    TOO_LONG,
    /** Refused: the document has {@link #MAX_COMMENTS} comments. */
    TOO_MANY
  }

  /**
   * One bookmark as {@link #marks} lists it.
   *
   * @param offset its line's start
   * @param comment its comment, or null
   * @param key identifies the bookmark for {@link #line(Object)}
   */
  public record Mark(int offset, String comment, Object key) {}

  /** A bookmark: its anchor, comment and offset before the change being reported. */
  private static final class Entry {
    Anchor anchor;
    String comment;
    int before;
    boolean gone;

    Entry(Anchor anchor, String comment) {
      this.anchor = anchor;
      this.comment = comment;
      this.before = anchor.offset();
    }
  }

  private final Document document;
  private final List<Entry> entries = new ArrayList<Entry>();
  private final RemovedBookmarks removals;
  private final List<Runnable> listeners = new ArrayList<Runnable>();
  private int comments;

  /**
   * Creates an empty set that follows the document's changes until {@link #dispose}.
   *
   * @param document the document
   */
  public Bookmarks(Document document) {
    this.document = document;
    this.removals = new RemovedBookmarks(document);
    document.addListener(this);
  }

  /**
   * Returns a comment as it is stored: each line break and other control char replaced by a space
   * (CR LF by one), outer whitespace stripped.
   *
   * @param text any text, or null
   * @return the text; empty for null
   */
  public static String clean(String text) {
    if (text == null) {
      return "";
    }
    StringBuilder out = new StringBuilder(text.length());
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
        continue;
      }
      boolean control = c < 0x20 || (c >= 0x7f && c < 0xa0) || c == 0x2028 || c == 0x2029;
      out.append(control ? ' ' : c);
    }
    return out.toString().strip();
  }

  /**
   * Returns the bookmarked lines.
   *
   * @return distinct zero-based lines, ascending
   */
  public int[] lines() {
    TextSnapshot text = document.text();
    int[] lines = new int[entries.size()];
    for (int i = 0; i < lines.length; i++) {
      lines[i] = text.lineAt(entries.get(i).anchor.offset());
    }
    Arrays.sort(lines);
    int n = 0;
    for (int i = 0; i < lines.length; i++) {
      if (n == 0 || lines[i] != lines[n - 1]) {
        lines[n++] = lines[i];
      }
    }
    return Arrays.copyOf(lines, n);
  }

  /**
   * Returns the lines whose bookmark has a comment.
   *
   * @return zero-based lines, ascending
   */
  public int[] commentedLines() {
    TextSnapshot text = document.text();
    int[] lines = new int[comments];
    int n = 0;
    for (Entry e : entries) {
      if (e.comment != null) {
        lines[n++] = text.lineAt(e.anchor.offset());
      }
    }
    Arrays.sort(lines, 0, n);
    return Arrays.copyOf(lines, n);
  }

  /**
   * Returns the bookmarks by offset, for a worker that lists them.
   *
   * @return one entry per bookmark, ascending
   */
  public List<Mark> marks() {
    List<Mark> list = new ArrayList<Mark>(entries.size());
    for (Entry e : entries) {
      list.add(new Mark(e.anchor.offset(), e.comment, e));
    }
    list.sort(Comparator.comparingInt(Mark::offset));
    return list;
  }

  /**
   * Counts the comments.
   *
   * @return bookmarks with a comment
   */
  public int commentCount() {
    return comments;
  }

  /**
   * Returns a line's comment.
   *
   * @param line a zero-based line
   * @return the comment, or null when the line has no bookmark or its bookmark none
   */
  public String comment(int line) {
    Entry e = entry(line);
    return e == null ? null : e.comment;
  }

  /**
   * Returns the line a listed bookmark marks now.
   *
   * @param key a {@link Mark#key}
   * @return the zero-based line, or -1 when the bookmark was removed
   */
  public int line(Object key) {
    for (Entry e : entries) {
      if (e == key) {
        return document.text().lineAt(e.anchor.offset());
      }
    }
    return -1;
  }

  /**
   * Reports whether a line is bookmarked.
   *
   * @param line a zero-based line
   * @return true when it holds a bookmark
   */
  public boolean contains(int line) {
    return entry(line) != null;
  }

  /**
   * Bookmarks a line, or removes its bookmark and comment.
   *
   * @param line a zero-based line of the document
   */
  public void toggle(int line) {
    if (!remove(line)) {
      add(line, null);
      fire();
    }
  }

  /**
   * Removes a line's bookmark and comment.
   *
   * @param line a zero-based line
   * @return false when the line had no bookmark
   */
  public boolean remove(int line) {
    Entry e = entry(line);
    if (e == null) {
      return false;
    }
    drop(e);
    fire();
    return true;
  }

  /**
   * Sets a line's comment, bookmarking the line when it is not; an empty comment keeps the bookmark
   * without one. The text is stored as {@link #clean} returns it. A refusal changes nothing.
   *
   * @param line a zero-based line of the document
   * @param text the comment
   * @return what was done
   */
  public Result setComment(int line, String text) {
    String value = clean(text);
    if (value.length() > MAX_COMMENT_LENGTH) {
      return Result.TOO_LONG;
    }
    Entry e = entry(line);
    boolean adds = !value.isEmpty() && (e == null || e.comment == null);
    if (adds && comments >= MAX_COMMENTS) {
      return Result.TOO_MANY;
    }
    if (e == null) {
      e = add(line, null);
    }
    setComment(e, value.isEmpty() ? null : value);
    fire();
    return Result.DONE;
  }

  /**
   * Returns the next bookmarked line after a line, or before it, wrapping around.
   *
   * @param line the line to start from
   * @param forward true for the next line down
   * @return the line, which is {@code line} itself when it holds the only bookmark, or -1 when
   *     there is none
   */
  public int next(int line, boolean forward) {
    int[] lines = lines();
    if (lines.length == 0) {
      return -1;
    }
    if (forward) {
      for (int l : lines) {
        if (l > line) {
          return l;
        }
      }
      return lines[0];
    }
    for (int i = lines.length - 1; i >= 0; i--) {
      if (lines[i] < line) {
        return lines[i];
      }
    }
    return lines[lines.length - 1];
  }

  /** Removes every bookmark and comment; an undo restores none of them. */
  public void clear() {
    if (!entries.isEmpty() || !removals.isEmpty()) {
      boolean any = !entries.isEmpty();
      release();
      if (any) {
        fire();
      }
    }
  }

  /** Removes every bookmark and stops following the document, without notifying the listeners. */
  public void dispose() {
    release();
    document.removeListener(this);
  }

  /**
   * Adds what runs after a toggle, a clear, a comment change, and an edit, undo or redo that
   * removes or restores a bookmark; other edits notify no one, since a view repaints on them.
   *
   * @param listener the listener
   */
  public void addListener(Runnable listener) {
    listeners.add(listener);
  }

  /**
   * Removes a listener.
   *
   * @param listener the listener
   */
  public void removeListener(Runnable listener) {
    listeners.remove(listener);
  }

  @Override
  public void textChanged(DocumentChange change) {
    List<TextEdit> edits = change.edits();
    boolean changed = false;
    if (change.cause() != DocumentChange.Cause.PREEDIT && removes(edits)) {
      changed = removeDeleted(edits);
    }
    if (change.cause() == DocumentChange.Cause.UNDO
        || change.cause() == DocumentChange.Cause.REDO) {
      changed |= restore();
    }
    for (Entry e : entries) {
      e.before = e.anchor.offset();
    }
    if (changed) {
      fire();
    }
  }

  @Override
  public void dirtyChanged(boolean dirty) {
    // Bookmarks follow the text only.
  }

  private static boolean removes(List<TextEdit> edits) {
    for (TextEdit edit : edits) {
      if (!edit.removed().isEmpty()) {
        return true;
      }
    }
    return false;
  }

  /**
   * Moves each bookmark whose line an edit replaced to that line's text in the inserted text, and
   * removes the bookmarks of deleted lines and the lower of two joined ones; true when any was
   * removed.
   */
  private boolean removeDeleted(List<TextEdit> edits) {
    int[] starts = new int[edits.size()];
    int[] shifts = new int[edits.size()];
    int shift = 0;
    boolean joins = false;
    for (int i = 0; i < starts.length; i++) {
      TextEdit edit = edits.get(i);
      starts[i] = edit.offset();
      shifts[i] = shift;
      shift += edit.inserted().length() - edit.removed().length();
      joins |= edit.removedLines() > 0;
    }
    int beforeLength = document.text().length() - shift;
    removals.startChange(edits.size());
    List<List<Entry>> replaced = new ArrayList<List<Entry>>(edits.size());
    for (int i = 0; i < edits.size(); i++) {
      replaced.add(null);
    }
    for (Entry e : entries) {
      e.gone = false;
      int at = editAt(edits, starts, e.before);
      if (at < 0) {
        continue;
      }
      TextEdit edit = edits.get(at);
      e.gone = deletes(edit, e.before, beforeLength);
      if (e.before < edit.offset() + edit.removed().length() && !edit.inserted().isEmpty()) {
        if (replaced.get(at) == null) {
          replaced.set(at, new ArrayList<Entry>());
        }
        replaced.get(at).add(e);
      }
    }
    for (int at = 0; at < edits.size(); at++) {
      if (replaced.get(at) != null) {
        follow(edits.get(at), shifts[at], replaced.get(at));
      }
    }
    boolean changed = false;
    List<Entry> kept = new ArrayList<Entry>(entries.size());
    for (Entry e : entries) {
      if (e.gone) {
        keep(edits, shifts, editAt(edits, starts, e.before), e);
        changed = true;
      } else {
        kept.add(e);
      }
    }
    entries.clear();
    entries.addAll(kept);
    if (joins && entries.size() > 1) {
      changed |= removeJoined(edits, starts, shifts);
    }
    removals.endChange();
    return changed;
  }

  /**
   * Moves each bookmark whose line text starts in an edit's removed text to the first unused line
   * of the inserted text with the same text. When the edit ends inside a line, a line whose break
   * the edit removed matches only a line of the inserted text ending in a break, and the line the
   * edit ends in matches only the inserted text's last line. Moved and sorted lines keep their
   * bookmarks this way.
   */
  private void follow(TextEdit edit, int shift, List<Entry> list) {
    String from = edit.removed();
    String into = edit.inserted();
    int a = edit.offset();
    int n = list.size();
    String[] wanted = new String[n];
    boolean[] ended = new boolean[n];
    boolean[] used = new boolean[n];
    Map<Integer, List<Integer>> byHash = new HashMap<Integer, List<Integer>>();
    for (int i = 0; i < n; i++) {
      int s = list.get(i).before - a;
      int j = s;
      while (j < from.length() && !lineBreak(from.charAt(j))) {
        j++;
      }
      wanted[i] = from.substring(s, j);
      ended[i] = j < from.length();
      Integer key = wanted[i].hashCode();
      if (!byHash.containsKey(key)) {
        byHash.put(key, new ArrayList<Integer>());
      }
      byHash.get(key).add(i);
    }
    TextSnapshot text = document.text();
    int after = a + shift + into.length();
    boolean lineEnd = after == text.length() || lineBreak(text.charAt(after));
    int start = a + shift;
    int p = 0;
    if (start > 0 && !lineBreak(text.charAt(start - 1))) {
      p = nextLine(into, 0);
    }
    while (p >= 0) {
      int q = p;
      int h = 0;
      while (q < into.length() && !lineBreak(into.charAt(q))) {
        h = 31 * h + into.charAt(q);
        q++;
      }
      boolean lineEnded = q < into.length();
      List<Integer> same = byHash.get(h);
      if (same != null) {
        for (int i : same) {
          if (!used[i]
              && (lineEnd || ended[i] == lineEnded)
              && wanted[i].length() == q - p
              && into.regionMatches(p, wanted[i], 0, q - p)) {
            used[i] = true;
            Entry e = list.get(i);
            e.anchor.dispose();
            e.anchor = document.anchor(start + p, Bias.AFTER);
            e.gone = false;
            break;
          }
        }
      }
      p = lineEnded ? nextLine(into, q) : -1;
    }
  }

  private static boolean lineBreak(char c) {
    return c == '\n' || c == '\r';
  }

  /** Returns the start of the line after the break at or after {@code from}; -1 without one. */
  private static int nextLine(String text, int from) {
    int i = from;
    while (i < text.length() && !lineBreak(text.charAt(i))) {
      i++;
    }
    if (i == text.length()) {
      return -1;
    }
    return text.charAt(i) == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n'
        ? i + 2
        : i + 1;
  }

  /** Keeps the upper of the bookmarks that share a line; true when any was removed. */
  private boolean removeJoined(List<TextEdit> edits, int[] starts, int[] shifts) {
    TextSnapshot text = document.text();
    List<Entry> sorted = new ArrayList<Entry>(entries);
    int[] lines = new int[sorted.size()];
    sorted.sort(
        new Comparator<Entry>() {
          @Override
          public int compare(Entry x, Entry y) {
            return Integer.compare(x.before, y.before);
          }
        });
    for (int i = 0; i < lines.length; i++) {
      lines[i] = text.lineAt(sorted.get(i).anchor.offset());
    }
    boolean changed = false;
    int kept = 0;
    for (int i = 1; i < lines.length; i++) {
      if (lines[i] == lines[kept]) {
        Entry e = sorted.get(i);
        int at = editAt(edits, starts, e.before);
        if (at >= 0) {
          keep(edits, shifts, at, e);
        } else {
          releaseEntry(e);
        }
        entries.remove(e);
        changed = true;
      } else {
        kept = i;
      }
    }
    return changed;
  }

  /** Moves a removed bookmark into the record of the edit that removed it. */
  private void keep(List<TextEdit> edits, int[] shifts, int at, Entry e) {
    removals.keep(edits.get(at), at, shifts[at], e.before, e.comment);
    releaseEntry(e);
  }

  /** Returns the edit whose removed range holds an offset, its end included; -1 when none. */
  private static int editAt(List<TextEdit> edits, int[] starts, int offset) {
    int i = Arrays.binarySearch(starts, offset);
    if (i < 0) {
      i = -i - 2;
    }
    if (i < 0) {
      return -1;
    }
    TextEdit edit = edits.get(i);
    if (edit.removed().isEmpty() || offset > edit.offset() + edit.removed().length()) {
      return -1;
    }
    return i;
  }

  /**
   * Reports whether an edit removes the line starting at {@code start}: its text and at least the
   * first char of its break, or, ending the text, its text and the break before it.
   */
  private static boolean deletes(TextEdit edit, int start, int beforeLength) {
    String text = edit.removed();
    int a = edit.offset();
    int b = a + text.length();
    for (int j = start - a; j < text.length(); j++) {
      char c = text.charAt(j);
      if (lineBreak(c)) {
        return true;
      }
    }
    return a < start && b == beforeLength;
  }

  /** Restores the bookmarks of each kept edit whose removed text an undo or redo put back. */
  private boolean restore() {
    if (removals.isEmpty()) {
      return false;
    }
    return removals.restore(document.text(), this);
  }

  /** Restores one removed bookmark with its comment, when the comment limit allows. */
  void restore(TextSnapshot text, int offset, String comment) {
    int line = text.lineAt(Math.min(offset, text.length()));
    Entry e = entry(line);
    if (e == null) {
      e = add(line, null);
    }
    if (comment != null && e.comment == null && comments < MAX_COMMENTS) {
      setComment(e, comment);
    }
  }

  private Entry entry(int line) {
    TextSnapshot text = document.text();
    for (Entry e : entries) {
      if (text.lineAt(e.anchor.offset()) == line) {
        return e;
      }
    }
    return null;
  }

  private Entry add(int line, String comment) {
    Entry e = new Entry(document.anchor(document.text().offsetOfLine(line), Bias.AFTER), null);
    entries.add(e);
    setComment(e, comment);
    return e;
  }

  private void setComment(Entry e, String comment) {
    if (e.comment != null) {
      comments--;
    }
    e.comment = comment;
    if (comment != null) {
      comments++;
    }
  }

  private void drop(Entry e) {
    entries.remove(e);
    releaseEntry(e);
  }

  private void releaseEntry(Entry e) {
    setComment(e, null);
    e.anchor.dispose();
  }

  private void release() {
    for (Entry e : entries) {
      releaseEntry(e);
    }
    entries.clear();
    removals.clear();
  }

  private void fire() {
    for (Runnable listener : new ArrayList<Runnable>(listeners)) {
      listener.run();
    }
  }
}
