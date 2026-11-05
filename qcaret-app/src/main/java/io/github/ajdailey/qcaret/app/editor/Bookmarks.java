package io.github.ajdailey.qcaret.app.editor;

import io.github.ajdailey.qcaret.core.buffer.TextSnapshot;
import io.github.ajdailey.qcaret.core.document.Anchor;
import io.github.ajdailey.qcaret.core.document.Bias;
import io.github.ajdailey.qcaret.core.document.Document;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The bookmarked lines of one document, shared by its views. Each bookmark is an {@link Anchor} at
 * its line's start with {@link Bias#AFTER}, so a line break inserted at that start moves it down
 * with the line's text, and it marks whatever line holds the anchor. Two bookmarks that land on one
 * line, after the lines between them are deleted, count as one. Confined to the document's thread.
 */
public final class Bookmarks {
  private final Document document;
  private final List<Anchor> anchors = new ArrayList<Anchor>();
  private final List<Runnable> listeners = new ArrayList<Runnable>();

  /**
   * Creates an empty set.
   *
   * @param document the document
   */
  public Bookmarks(Document document) {
    this.document = document;
  }

  /**
   * Returns the bookmarked lines.
   *
   * @return distinct zero-based lines, ascending
   */
  public int[] lines() {
    TextSnapshot text = document.text();
    int[] lines = new int[anchors.size()];
    for (int i = 0; i < lines.length; i++) {
      lines[i] = text.lineAt(anchors.get(i).offset());
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
   * Bookmarks a line, or removes its bookmark.
   *
   * @param line a zero-based line of the document
   */
  public void toggle(int line) {
    TextSnapshot text = document.text();
    boolean removed = false;
    for (int i = anchors.size() - 1; i >= 0; i--) {
      if (text.lineAt(anchors.get(i).offset()) == line) {
        anchors.remove(i).dispose();
        removed = true;
      }
    }
    if (!removed) {
      anchors.add(document.anchor(text.offsetOfLine(line), Bias.AFTER));
    }
    fire();
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

  /** Removes every bookmark. */
  public void clear() {
    if (!anchors.isEmpty()) {
      dispose();
      fire();
    }
  }

  /** Removes every bookmark without notifying the listeners, as the document closes. */
  public void dispose() {
    for (Anchor anchor : anchors) {
      anchor.dispose();
    }
    anchors.clear();
  }

  /**
   * Adds what runs after a toggle or a clear; edits notify no one, since a view repaints on them.
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

  private void fire() {
    for (Runnable listener : new ArrayList<Runnable>(listeners)) {
      listener.run();
    }
  }
}
