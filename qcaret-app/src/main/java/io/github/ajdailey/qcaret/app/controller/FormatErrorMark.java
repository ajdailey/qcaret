package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.view.EditorView;
import io.github.ajdailey.qcaret.core.buffer.TextContent;
import io.github.ajdailey.qcaret.core.document.Document;
import io.github.ajdailey.qcaret.core.document.DocumentChange;
import io.github.ajdailey.qcaret.core.document.DocumentListener;

/**
 * One view's format error mark: the token at a format error's offset ({@link #span}), shown by
 * {@link EditorView#showErrorMark} and in the overview strip. A document change clears it; {@link
 * FormatRuns} replaces it on the next error and clears it on the next run that completes without
 * one. Confined to the UI thread.
 */
final class FormatErrorMark implements DocumentListener {
  private final EditorController editor;
  private final Document document;
  private int start = -1;
  private int end = -1;

  FormatErrorMark(EditorController editor) {
    this.editor = editor;
    this.document = editor.document();
  }

  /**
   * Marks the token at an offset of the current text.
   *
   * @param offset the error's offset
   */
  void set(int offset) {
    if (start < 0) {
      document.addListener(this);
    }
    int[] span = span(document.text(), offset);
    start = span[0];
    end = span[1];
    show();
  }

  /** Removes the mark. */
  void clear() {
    if (start < 0) {
      return;
    }
    document.removeListener(this);
    start = -1;
    end = -1;
    show();
  }

  /**
   * Returns the mark's start.
   *
   * @return a model offset, or -1 for none
   */
  int start() {
    return start;
  }

  /**
   * Returns the mark's end.
   *
   * @return a model offset, equal to the start at a line end; -1 for none
   */
  int end() {
    return end;
  }

  /**
   * Returns the marked line.
   *
   * @return the line, from 0; -1 for none
   */
  int line() {
    return start < 0 ? -1 : document.text().lineAt(start);
  }

  /** Shows the mark in a newly attached view. */
  void viewAttached() {
    if (start >= 0 && editor.view() != null) {
      editor.view().showErrorMark(start, end);
    }
  }

  /** Stops following the document. */
  void dispose() {
    document.removeListener(this);
  }

  @Override
  public void textChanged(DocumentChange change) {
    clear();
  }

  @Override
  public void dirtyChanged(boolean dirty) {
    // The mark follows the text only.
  }

  private void show() {
    if (editor.view() != null) {
      editor.view().showErrorMark(start, end);
    }
    if (editor.overview() != null) {
      editor.overview().request();
    }
  }

  /**
   * Returns the range marked for an error at an offset. A run of letters, digits and {@code _-.:}
   * (an XML name, a JSON literal or number) holding the offset is marked whole. Otherwise the char
   * or surrogate pair at the offset is marked, with the run that ends there: the parser reports a
   * name joined to what follows at the first char after it. At a line break or the end of the text
   * only that run is marked, or nothing.
   *
   * @param text the text
   * @param offset the error's offset
   * @return {start, end}
   */
  static int[] span(TextContent text, int offset) {
    int length = text.length();
    int at = Math.min(offset, length);
    int start = at;
    while (start > 0 && nameChar(text.charAt(start - 1))) {
      start--;
    }
    if (at == length || text.charAt(at) == '\n' || text.charAt(at) == '\r') {
      return new int[] {start, at};
    }
    if (!nameChar(text.charAt(at))) {
      boolean pair =
          Character.isHighSurrogate(text.charAt(at))
              && at + 1 < length
              && Character.isLowSurrogate(text.charAt(at + 1));
      return new int[] {start, at + (pair ? 2 : 1)};
    }
    int end = at;
    while (end < length && nameChar(text.charAt(end))) {
      end++;
    }
    return new int[] {start, end};
  }

  private static boolean nameChar(char c) {
    return Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '.' || c == ':';
  }
}
