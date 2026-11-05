package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.config.Preferences;
import io.github.ajdailey.qcaret.app.config.SearchToggles;
import io.github.ajdailey.qcaret.app.editor.DisplayProjection;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.view.FindListener;
import io.github.ajdailey.qcaret.app.view.FindState;
import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import io.github.ajdailey.qcaret.core.buffer.TextSnapshot;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.WorkerThread;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.document.Document;
import io.github.ajdailey.qcaret.core.document.DocumentChange;
import io.github.ajdailey.qcaret.core.document.DocumentListener;
import io.github.ajdailey.qcaret.core.document.EditBatch;
import io.github.ajdailey.qcaret.core.document.Replacement;
import io.github.ajdailey.qcaret.core.document.Selection;
import io.github.ajdailey.qcaret.core.document.SelectionSet;
import io.github.ajdailey.qcaret.core.document.TextEdit;
import io.github.ajdailey.qcaret.core.search.Match;
import io.github.ajdailey.qcaret.core.search.PatternTooComplexException;
import io.github.ajdailey.qcaret.core.search.ReplaceTemplate;
import io.github.ajdailey.qcaret.core.search.SearchQuery;
import io.github.ajdailey.qcaret.core.search.TextMatcher;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.RejectedExecutionException;
import java.util.regex.PatternSyntaxException;

/**
 * The window's find bar: the query, its options and the replacement, applied to the selected
 * document. Confined to the UI thread; every match is computed on a {@code qcaret-cpu-} worker from
 * a snapshot, with cancel.
 *
 * <ul>
 *   <li>Find, Find Next and Find Previous select a match in the focused view, searching from its
 *       selection to the end of the document and then from the start (from its selection to the
 *       start, then from the end, for Previous); a wrap shows a notice. Typing in the bar finds
 *       from where the selection started when the bar opened or the last match was found.
 *   <li>While the bar is open, each view of the selected document highlights the matches in its
 *       visible range, at most {@value #VIEW_MATCHES}. A text change shifts the highlights at once
 *       and recomputes them. Esc in a view with one caret and no block closes the bar.
 *   <li>Replace replaces the focused view's selection when the query matches exactly that range,
 *       then finds the next match. Replace All replaces every match in the document. Each is one
 *       undo step; line breaks in the replacement become the document's line ending.
 *   <li>Every result carries the operation's generation and the document revision it read; a result
 *       from an older operation or an older revision is dropped. A regex that does not compile or
 *       overflows the stack, and a replacement that does not parse, end with a notice in the bar.
 * </ul>
 */
public final class FindController implements DocumentController.ViewListener {
  /** Matches highlighted in one view at most. */
  static final int VIEW_MATCHES = 5000;

  /** Longest selection Find takes as its text. */
  static final int SEED_CHARS = 1000;

  private final DocumentController documents;
  private final Workers workers;
  private final UiDispatcher ui;
  private final Settings settings;
  private final List<FindListener> listeners = new ArrayList<FindListener>();
  private final Map<EditorController, CancellationToken> highlighting =
      new IdentityHashMap<EditorController, CancellationToken>();
  private final Map<EditorController, int[]> shown = new IdentityHashMap<EditorController, int[]>();
  private final DocumentListener changes =
      new DocumentListener() {
        @Override
        public void textChanged(DocumentChange change) {
          shift(change);
        }

        @Override
        public void dirtyChanged(boolean dirty) {
          // Highlights follow the text only.
        }
      };
  private boolean open;
  private boolean replace;
  private String text = "";
  private String replacement = "";
  private String notice = "";
  private boolean error;
  private TextMatcher matcher;
  private CancellationToken finding;
  private long generation;
  private Document followed;
  private EditorController target;
  private int anchor;
  private boolean disposed;

  /**
   * Creates the controller and starts following the window's views.
   *
   * @param documents the window's documents
   * @param workers runs the searches
   * @param ui receives the results
   * @param settings holds the search toggles
   */
  public FindController(
      DocumentController documents, Workers workers, UiDispatcher ui, Settings settings) {
    this.documents = documents;
    this.workers = workers;
    this.ui = ui;
    this.settings = settings;
    documents.setViewListener(this);
  }

  /**
   * Adds a listener.
   *
   * @param listener called on the UI thread
   */
  public void addListener(FindListener listener) {
    listeners.add(listener);
  }

  /**
   * Removes a listener.
   *
   * @param listener a listener added earlier
   */
  public void removeListener(FindListener listener) {
    listeners.remove(listener);
  }

  /**
   * Returns what the bar shows.
   *
   * @return the state
   */
  public FindState state() {
    Document document = documents.activeDocument();
    boolean editable = document != null && !document.isReadOnly();
    return new FindState(
        open,
        replace,
        text,
        replacement,
        toggles().caseSensitive(),
        toggles().wholeWord(),
        toggles().regex(),
        editable && matcher != null,
        finding != null,
        notice,
        error);
  }

  // Bar commands.

  /**
   * Opens the bar and focuses its field. A selection within one line, up to {@value #SEED_CHARS}
   * chars, becomes the search text.
   *
   * @param withReplace true to show the replace row
   */
  public void open(boolean withReplace) {
    if (disposed) {
      return;
    }
    open = true;
    replace = withReplace;
    boolean seeded = false;
    EditorController editor = documents.activeEditor();
    if (editor != null) {
      Selection primary = editor.selections().selections().primary();
      anchor = primary.start();
      String seed = seed(editor.document().text(), primary);
      if (seed != null && !seed.equals(text)) {
        text = seed;
        seeded = true;
      }
    }
    compile();
    refreshHighlights();
    if (seeded && matcher != null) {
      find(true, anchor, false);
    } else {
      changed();
    }
    for (FindListener listener : new ArrayList<FindListener>(listeners)) {
      listener.focusRequested();
    }
  }

  private static String seed(TextSnapshot text, Selection selection) {
    int length = selection.end() - selection.start();
    if (length == 0 || length > SEED_CHARS) {
      return null;
    }
    String selected = text.subSequence(selection.start(), selection.end()).toString();
    return selected.indexOf('\n') >= 0 || selected.indexOf('\r') >= 0 ? null : selected;
  }

  /** Closes the bar, removes the highlights and focuses the selected view. */
  public void close() {
    if (!open) {
      return;
    }
    open = false;
    documents.refreshOverview();
    cancelFind();
    clearHighlights(new ArrayList<EditorController>(shown.keySet()));
    notice = "";
    error = false;
    changed();
    EditorController editor = documents.activeEditor();
    if (editor != null && editor.view() != null) {
      editor.view().focus();
    }
  }

  /**
   * Sets the search text and finds its first match from the anchor.
   *
   * @param value the text
   */
  public void setText(String value) {
    if (value.equals(text)) {
      return;
    }
    text = value;
    queryChanged();
  }

  /**
   * Sets the options and finds again from the anchor.
   *
   * @param matchCase the Match Case box
   * @param word the Whole Word box
   * @param pattern the Regex box
   */
  public void setOptions(boolean matchCase, boolean word, boolean pattern) {
    Preferences current = settings.current();
    SearchToggles next =
        new SearchToggles(pattern, matchCase, word, current.state().search().hiddenFiles());
    settings.set(current.withState(current.state().withSearch(next)));
  }

  private SearchToggles toggles() {
    return settings.current().state().search();
  }

  /** The shared toggles changed: finds again from the anchor, or updates the bar. */
  void togglesChanged() {
    if (!disposed) {
      queryChanged();
    }
  }

  /**
   * Sets the replacement text.
   *
   * @param value the text; for a regex, see {@link ReplaceTemplate}
   */
  public void setReplacement(String value) {
    replacement = value;
  }

  private void queryChanged() {
    compile();
    refreshHighlights();
    if (matcher != null && open) {
      find(true, anchor, false);
    } else {
      cancelFind();
      changed();
    }
  }

  /**
   * Returns the query the overview strips mark while the bar is open.
   *
   * @param document a document
   * @return the matcher for the selected document while the bar is open with a valid query; null
   *     otherwise
   */
  TextMatcher overviewMatcher(Document document) {
    return open && !disposed && document == documents.activeDocument() ? matcher : null;
  }

  private void compile() {
    documents.refreshOverview();
    matcher = null;
    if (text.isEmpty()) {
      notice = "";
      error = false;
      return;
    }
    try {
      SearchToggles toggles = toggles();
      matcher =
          new SearchQuery(text, toggles.regex(), toggles.caseSensitive(), toggles.wholeWord())
              .compile();
      notice = "";
      error = false;
    } catch (PatternSyntaxException e) {
      notice = UiText.FIND_BAD_PATTERN.format(e.getDescription(), e.getIndex());
      error = true;
    }
  }

  /** Selects the next match after the focused view's selection; opens the bar without a text. */
  public void findNext() {
    next(true);
  }

  /** Selects the match before the focused view's selection; opens the bar without a text. */
  public void findPrevious() {
    next(false);
  }

  private void next(boolean forward) {
    EditorController editor = documents.activeEditor();
    if (editor == null || disposed) {
      return;
    }
    if (matcher == null) {
      open(replace);
      return;
    }
    Selection primary = editor.selections().selections().primary();
    find(forward, forward ? primary.end() : primary.start(), true);
  }

  /** Replaces the selection when it is a match, then selects the next match. */
  public void replace() {
    final EditorController editor = documents.activeEditor();
    final ReplaceTemplate template = template();
    if (editor == null || template == null || editor.document().isReadOnly()) {
      return;
    }
    final Selection primary = editor.selections().selections().primary();
    final Document document = editor.document();
    document.clearPreedit();
    final TextSnapshot snapshot = document.text();
    final long revision = document.revision();
    final String ending = document.lineEnding().text();
    final TextMatcher m = matcher;
    run(
        new Task() {
          @Override
          @WorkerThread
          public Runnable compute(CancellationToken token) {
            String with =
                m.replacementAt(snapshot, primary.start(), primary.end(), template, token);
            final String converted =
                with == null ? null : EditorController.convertBreaks(with, ending);
            return new Runnable() {
              @Override
              public void run() {
                if (document.revision() != revision) {
                  report(UiText.FIND_REPLACE_STALE.text(), true);
                  return;
                }
                int after = primary.end();
                if (converted != null && !document.isReadOnly()) {
                  document.apply(
                      EditBatch.replace(
                          primary.start(), primary.end() - primary.start(), converted),
                      editor.selections(),
                      SelectionSet.caret(primary.start() + converted.length()));
                  after = primary.start() + converted.length();
                }
                find(true, after, true);
              }
            };
          }
        });
  }

  /** Replaces every match in the selected document as one undo step. */
  public void replaceAll() {
    final EditorController editor = documents.activeEditor();
    final ReplaceTemplate template = template();
    if (editor == null || template == null || editor.document().isReadOnly()) {
      return;
    }
    final Document document = editor.document();
    document.clearPreedit();
    final TextSnapshot snapshot = document.text();
    final long revision = document.revision();
    final String ending = document.lineEnding().text();
    final int caret = editor.selections().selections().primary().caret();
    final TextMatcher m = matcher;
    run(
        new Task() {
          @Override
          @WorkerThread
          public Runnable compute(CancellationToken token) {
            List<Replacement> found = m.replaceAll(snapshot, template, token);
            final List<Replacement> replacements = new ArrayList<Replacement>(found.size());
            for (Replacement r : found) {
              replacements.add(
                  new Replacement(
                      r.offset(),
                      r.removeLength(),
                      EditorController.convertBreaks(r.text(), ending)));
            }
            return new Runnable() {
              @Override
              public void run() {
                if (document.revision() != revision) {
                  report(UiText.FIND_REPLACE_ALL_STALE.text(), true);
                  return;
                }
                if (document.isReadOnly()) {
                  return;
                }
                if (replacements.isEmpty()) {
                  report(UiText.FIND_NOT_FOUND.text(), true);
                  return;
                }
                document.apply(
                    EditBatch.of(EditBatch.Kind.COMMAND, replacements),
                    editor.selections(),
                    SelectionSet.caret(mapped(caret, replacements)));
                if (editor.view() != null) {
                  editor.view().showCaret();
                }
                report(UiText.FIND_REPLACED.format(replacements.size()), false);
              }
            };
          }
        });
  }

  /** Where an offset lands after replacements: past one that contains it. */
  static int mapped(int offset, List<Replacement> replacements) {
    int shift = 0;
    for (Replacement r : replacements) {
      if (r.offset() >= offset) {
        break;
      }
      if (r.end() > offset) {
        return r.offset() + shift + r.text().length();
      }
      shift += r.text().length() - r.removeLength();
    }
    return offset + shift;
  }

  private ReplaceTemplate template() {
    if (matcher == null) {
      return null;
    }
    try {
      return matcher.template(replacement);
    } catch (IllegalArgumentException e) {
      report(UiText.FIND_BAD_REPLACEMENT.format(e.getMessage()), true);
      return null;
    }
  }

  /** Stops the running search and the highlights and stops following the document. */
  public void dispose() {
    disposed = true;
    cancelFind();
    for (CancellationToken token : highlighting.values()) {
      token.cancel();
    }
    highlighting.clear();
    follow(null);
  }

  // Finding.

  /** One search: computes on a worker, returns what the UI thread applies. */
  private interface Task {
    @WorkerThread
    Runnable compute(CancellationToken token);
  }

  /**
   * Searches from an offset in the focused view and selects the match.
   *
   * @param forward the direction
   * @param from where the search starts
   * @param move true when the user asked for the next match: it becomes the anchor
   */
  private void find(final boolean forward, final int from, final boolean move) {
    final EditorController editor = documents.activeEditor();
    if (editor == null || matcher == null) {
      return;
    }
    final Document document = editor.document();
    final TextSnapshot snapshot = document.text();
    final long revision = document.revision();
    final TextMatcher m = matcher;
    run(
        new Task() {
          @Override
          @WorkerThread
          public Runnable compute(CancellationToken token) {
            final int start = Math.min(from, snapshot.length());
            Match found =
                forward ? m.next(snapshot, start, token) : m.previous(snapshot, start, token);
            boolean wrapped = false;
            if (found == null) {
              found =
                  forward
                      ? m.next(snapshot, 0, token)
                      : m.previous(snapshot, snapshot.length() + 1, token);
              wrapped = found != null;
            }
            final Match match = found;
            final boolean wrap = wrapped;
            return new Runnable() {
              @Override
              public void run() {
                if (document.revision() != revision) {
                  return;
                }
                if (match == null) {
                  report(UiText.FIND_NOT_FOUND.text(), true);
                  return;
                }
                editor
                    .selections()
                    .set(SelectionSet.of(List.of(Selection.of(match.start(), match.end())), 0));
                if (editor.view() != null) {
                  editor.view().showCaret();
                }
                if (move) {
                  anchor = match.start();
                }
                report(
                    !wrap
                        ? ""
                        : forward
                            ? UiText.FIND_WRAPPED_TOP.text()
                            : UiText.FIND_WRAPPED_BOTTOM.text(),
                    false);
              }
            };
          }
        });
  }

  /**
   * Runs a search on a worker, canceling the one before. The result applies on the UI thread while
   * its generation is current.
   */
  private void run(final Task task) {
    cancelFind();
    final long current = ++generation;
    final CancellationToken token = new CancellationToken();
    finding = token;
    changed();
    try {
      workers.executeCpu(
          new Runnable() {
            @Override
            public void run() {
              Runnable apply = null;
              String failure = null;
              try {
                apply = task.compute(token);
              } catch (CancellationException e) {
                return;
              } catch (PatternTooComplexException e) {
                failure = UiText.FIND_TOO_COMPLEX.text();
              }
              final Runnable result = apply;
              final String failed = failure;
              ui.asyncExec(
                  new Runnable() {
                    @Override
                    public void run() {
                      if (current != generation || disposed) {
                        return;
                      }
                      finding = null;
                      if (failed != null) {
                        report(failed, true);
                      } else {
                        result.run();
                        changed();
                      }
                    }
                  });
            }
          });
    } catch (RejectedExecutionException e) {
      finding = null;
    }
  }

  private void cancelFind() {
    generation++;
    if (finding != null) {
      finding.cancel();
      finding = null;
    }
  }

  private void report(String message, boolean failure) {
    notice = message;
    error = failure;
    changed();
  }

  private void changed() {
    FindState state = state();
    for (FindListener listener : new ArrayList<FindListener>(listeners)) {
      listener.findStateChanged(state);
    }
  }

  // Highlights.

  @Override
  public void escapePressed() {
    close();
  }

  @Override
  public void viewsChanged() {
    if (disposed) {
      return;
    }
    EditorController editor = documents.activeEditor();
    if (editor != target) {
      target = editor;
      documents.refreshOverview();
      cancelFind();
      if (editor != null) {
        anchor = editor.selections().selections().primary().start();
      }
    }
    refreshHighlights();
    changed();
  }

  @Override
  public void viewportChanged(EditorController editor) {
    if (open && matcher != null && !disposed) {
      highlight(editor);
    }
  }

  /** Recomputes the highlights of every view of the selected document; clears the others. */
  private void refreshHighlights() {
    List<EditorController> views =
        open && matcher != null && !disposed
            ? documents.activeEditors()
            : new ArrayList<EditorController>();
    List<EditorController> stale = new ArrayList<EditorController>();
    for (EditorController e : shown.keySet()) {
      if (!views.contains(e)) {
        stale.add(e);
      }
    }
    for (EditorController e : highlighting.keySet()) {
      if (!views.contains(e) && !stale.contains(e)) {
        stale.add(e);
      }
    }
    clearHighlights(stale);
    follow(views.isEmpty() ? null : views.get(0).document());
    for (EditorController e : views) {
      highlight(e);
    }
  }

  private void clearHighlights(List<EditorController> editors) {
    for (EditorController e : editors) {
      CancellationToken token = highlighting.remove(e);
      if (token != null) {
        token.cancel();
      }
      if (shown.remove(e) != null && e.view() != null) {
        e.view().showMatches(new int[0]);
      }
    }
  }

  private void follow(Document document) {
    if (document == followed) {
      return;
    }
    if (followed != null) {
      followed.removeListener(changes);
    }
    followed = document;
    if (document != null) {
      document.addListener(changes);
    }
  }

  /** Computes one view's visible matches on a worker. */
  private void highlight(final EditorController editor) {
    CancellationToken old = highlighting.get(editor);
    if (old != null) {
      old.cancel();
    }
    final int from = editor.visibleStart();
    final int to = editor.visibleEnd();
    final Document document = editor.document();
    final TextSnapshot snapshot = document.text();
    final long revision = document.revision();
    final TextMatcher m = matcher;
    final CancellationToken token = new CancellationToken();
    highlighting.put(editor, token);
    try {
      workers.executeCpu(
          new Runnable() {
            @Override
            public void run() {
              int[] ranges;
              try {
                ranges = visibleMatches(m, snapshot, from, to, token);
              } catch (CancellationException e) {
                return;
              } catch (PatternTooComplexException e) {
                ranges = new int[0];
              }
              final int[] found = ranges;
              ui.asyncExec(
                  new Runnable() {
                    @Override
                    public void run() {
                      if (highlighting.get(editor) != token || document.revision() != revision) {
                        return;
                      }
                      highlighting.remove(editor);
                      if (editor.view() != null) {
                        shown.put(editor, found);
                        editor.view().showMatches(found);
                      }
                    }
                  });
            }
          });
    } catch (RejectedExecutionException e) {
      highlighting.remove(editor);
    }
  }

  /** The matches starting in {@code [from, to)} and on the part of its first line before it. */
  @WorkerThread
  static int[] visibleMatches(
      TextMatcher m, TextSnapshot text, int from, int to, CancellationToken token) {
    int end = Math.min(to, text.length());
    if (end <= from) {
      return new int[0];
    }
    int lineStart = text.offsetOfLine(text.lineAt(from));
    int start = Math.max(lineStart, from - DisplayProjection.MAX_ROW_CHARS);
    List<Match> matches = m.all(text, start, end, VIEW_MATCHES, token);
    int[] ranges = new int[matches.size() * 2];
    for (int i = 0; i < matches.size(); i++) {
      ranges[2 * i] = matches.get(i).start();
      ranges[2 * i + 1] = matches.get(i).end();
    }
    return ranges;
  }

  /** Shifts the shown highlights through a change, drops those it touches, and recomputes. */
  private void shift(DocumentChange change) {
    List<EditorController> editors = new ArrayList<EditorController>(shown.keySet());
    for (EditorController e : editors) {
      int[] mappedRanges = shifted(shown.get(e), change.edits());
      shown.put(e, mappedRanges);
      if (e.view() != null) {
        e.view().showMatches(mappedRanges);
      }
    }
    if (open && matcher != null) {
      for (EditorController e : documents.activeEditors()) {
        highlight(e);
      }
    }
  }

  /** Maps ranges through edits given by ascending offset; a range an edit touches is dropped. */
  static int[] shifted(int[] ranges, List<TextEdit> edits) {
    int[] out = new int[ranges.length];
    int n = 0;
    int e = 0;
    int shift = 0;
    for (int i = 0; i + 1 < ranges.length; i += 2) {
      int start = ranges[i];
      int end = ranges[i + 1];
      while (e < edits.size() && edits.get(e).offset() + edits.get(e).removed().length() <= start) {
        TextEdit edit = edits.get(e);
        shift += edit.inserted().length() - edit.removed().length();
        e++;
      }
      boolean touched = e < edits.size() && edits.get(e).offset() < end;
      if (!touched) {
        out[n++] = start + shift;
        out[n++] = end + shift;
      }
    }
    return Arrays.copyOf(out, n);
  }
}
