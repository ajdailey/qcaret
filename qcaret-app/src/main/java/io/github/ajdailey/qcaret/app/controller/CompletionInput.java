package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.view.EditorView;
import io.github.ajdailey.qcaret.core.buffer.TextSnapshot;
import io.github.ajdailey.qcaret.core.document.Document;
import io.github.ajdailey.qcaret.core.document.EditBatch;
import io.github.ajdailey.qcaret.core.document.Replacement;
import io.github.ajdailey.qcaret.core.document.Selection;
import io.github.ajdailey.qcaret.core.document.SelectionModel;
import io.github.ajdailey.qcaret.core.document.SelectionSet;
import io.github.ajdailey.qcaret.core.edit.Edit;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * One view's word completion list ({@link WordCompletion}): the lookup, the list the view shows,
 * the keys that move in it, and the insert at every caret. Confined to the UI thread.
 */
final class CompletionInput {
  /** Rows Page Up and Page Down move in the completion list. */
  static final int COMPLETION_PAGE = 8;

  /** Word chars before the caret a completion prefix takes at most. */
  static final int MAX_PREFIX = 100;

  private final EditorController editor;
  private final Document document;
  private final SelectionModel selections;
  private WordCompletion completion;
  private int completionChars;
  private String completionPrefix;
  private List<String> completionAll;
  private List<String> completionShown;
  private int completionSelected;

  CompletionInput(EditorController editor) {
    this.editor = editor;
    this.document = editor.document();
    this.selections = editor.selections();
  }

  /** Sets the window's word completion. */
  void setCompletion(WordCompletion value) {
    completion = value;
  }

  /**
   * Sets how many word chars typed open the completion list on their own.
   *
   * @param chars the count, 0 for never
   */
  void setCompletionChars(int chars) {
    completionChars = chars;
  }

  /** Cancels a running lookup while the list shows. */
  void cancelLookup() {
    if (completionShown != null && completion != null) {
      completion.cancel();
    }
  }

  /** Cancels a running lookup and forgets the list without telling the view. */
  void release() {
    cancelLookup();
    completionShown = null;
  }

  /** Narrows the list after a selection change while it shows. */
  void selectionsChanged() {
    if (completionShown != null) {
      refilterCompletion();
    }
  }

  /** Returns the word chars before an offset, at most {@value #MAX_PREFIX}. */
  private String prefixAt(int caret) {
    TextSnapshot text = document.text();
    int start = caret;
    while (start > 0 && caret - start < MAX_PREFIX && isWordChar(text.charAt(start - 1))) {
      start--;
    }
    return text.subSequence(start, caret).toString();
  }

  private static boolean isWordChar(char c) {
    return Character.isLetterOrDigit(c) || c == '_';
  }

  /** Opens the completion list after a typed word char once enough of the word is typed. */
  void typed(String text) {
    if (completionChars > 0
        && completionShown == null
        && text.length() == 1
        && isWordChar(text.charAt(0))
        && prefixAt(selections.selections().primary().caret()).length() >= completionChars) {
      requestCompletion(false);
    }
  }

  /**
   * Looks up the words completing the one before the primary caret. An explicit request with one
   * word inserts it at once; otherwise the list shows.
   */
  void requestCompletion(final boolean explicit) {
    Selection primary = selections.selections().primary();
    if (completion == null || selections.block() != null || !primary.isEmpty()) {
      return;
    }
    final String prefix = prefixAt(primary.caret());
    if (prefix.isEmpty() || Character.isDigit(prefix.charAt(0))) {
      return;
    }
    final long revision = document.revision();
    final int caret = primary.caret();
    completion.request(
        document,
        prefix,
        new Consumer<List<String>>() {
          @Override
          public void accept(List<String> words) {
            completed(explicit, prefix, revision, caret, words);
          }
        });
  }

  private void completed(
      boolean explicit, String prefix, long revision, int caret, List<String> words) {
    Selection primary = selections.selections().primary();
    if (editor.disposed()
        || words.isEmpty()
        || document.revision() != revision
        || !primary.isEmpty()
        || primary.caret() != caret) {
      return;
    }
    completionPrefix = prefix;
    completionAll = words;
    completionShown = words;
    completionSelected = 0;
    EditorView view = editor.view();
    if (explicit && words.size() == 1) {
      acceptCompletion(0);
    } else if (view != null) {
      view.showCompletions(completionShown, 0);
    }
  }

  /** Narrows the list to the word now before the primary caret, or closes it. */
  private void refilterCompletion() {
    Selection primary = selections.selections().primary();
    String prefix =
        primary.isEmpty() && selections.block() == null ? prefixAt(primary.caret()) : "";
    if (prefix.length() < completionPrefix.length()
        || !prefix.regionMatches(true, 0, completionPrefix, 0, completionPrefix.length())) {
      closeCompletion();
      return;
    }
    List<String> shown = new ArrayList<String>();
    for (String word : completionAll) {
      if (word.regionMatches(true, 0, prefix, 0, prefix.length()) && !word.equals(prefix)) {
        shown.add(word);
      }
    }
    if (shown.isEmpty()) {
      closeCompletion();
      return;
    }
    if (!shown.equals(completionShown)) {
      completionShown = shown;
      completionSelected = 0;
    }
    EditorView view = editor.view();
    if (view != null) {
      view.showCompletions(completionShown, completionSelected);
    }
  }

  /**
   * Runs a command against the list while it shows; returns false when the list is closed or
   * ignores the command.
   */
  boolean key(CommandId command) {
    if (completionShown == null) {
      return false;
    }
    switch (command) {
      case CARET_UP -> selectCompletion(completionSelected - 1);
      case CARET_DOWN -> selectCompletion(completionSelected + 1);
      case PAGE_UP -> selectCompletion(completionSelected - COMPLETION_PAGE);
      case PAGE_DOWN -> selectCompletion(completionSelected + COMPLETION_PAGE);
      case NEWLINE, INSERT_TAB -> acceptCompletion(completionSelected);
      case CANCEL -> closeCompletion();
      case DELETE_BACK, COMPLETE_WORD -> {
        return false;
      }
      default -> {
        closeCompletion();
        return false;
      }
    }
    return true;
  }

  private void selectCompletion(int index) {
    completionSelected = Math.max(0, Math.min(completionShown.size() - 1, index));
    EditorView view = editor.view();
    if (view != null) {
      view.showCompletions(completionShown, completionSelected);
    }
  }

  /** Inserts the list's word at an index the view reports chosen, while the list shows. */
  void chosen(int index) {
    if (completionShown != null && index >= 0 && index < completionShown.size()) {
      acceptCompletion(index);
    }
  }

  /**
   * Replaces the typed prefix before every caret that ends with it, ignoring case, with a listed
   * word, as one undo step, and closes the list.
   */
  private void acceptCompletion(int index) {
    String word = completionShown.get(index);
    String prefix = prefixAt(selections.selections().primary().caret());
    closeCompletion();
    if (document.isReadOnly()) {
      return;
    }
    document.clearPreedit();
    TextSnapshot text = document.text();
    SelectionSet set = selections.selections();
    int n = prefix.length();
    List<Replacement> out = new ArrayList<Replacement>();
    List<Selection> after = new ArrayList<Selection>(set.size());
    int shift = 0;
    int lastEnd = 0;
    for (Selection s : set.selections()) {
      int at = s.caret();
      if (s.isEmpty()
          && at - n >= lastEnd
          && prefix.regionMatches(true, 0, text.subSequence(at - n, at).toString(), 0, n)) {
        out.add(new Replacement(at - n, n, word));
        shift += word.length() - n;
        after.add(Selection.caret(at + shift));
        lastEnd = at;
      } else {
        after.add(new Selection(s.anchor() + shift, s.caret() + shift, s.preferredColumn()));
      }
    }
    editor.applyEdit(
        new Edit(
            EditBatch.of(EditBatch.Kind.COMMAND, out), SelectionSet.of(after, set.primaryIndex())));
  }

  private void closeCompletion() {
    if (completionShown == null) {
      return;
    }
    completionShown = null;
    completionAll = null;
    completionPrefix = null;
    EditorView view = editor.view();
    if (view != null) {
      view.showCompletions(List.of(), 0);
    }
  }
}
