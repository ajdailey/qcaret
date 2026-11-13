package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.config.Preferences;
import io.github.ajdailey.qcaret.app.config.SearchToggles;
import io.github.ajdailey.qcaret.app.config.UiState;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.view.FindListener;
import io.github.ajdailey.qcaret.app.view.FindState;
import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import io.github.ajdailey.qcaret.core.buffer.TextSnapshot;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.WorkerThread;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.document.Document;
import io.github.ajdailey.qcaret.core.document.EditBatch;
import io.github.ajdailey.qcaret.core.document.Replacement;
import io.github.ajdailey.qcaret.core.document.Selection;
import io.github.ajdailey.qcaret.core.document.SelectionSet;
import io.github.ajdailey.qcaret.core.search.Match;
import io.github.ajdailey.qcaret.core.search.PatternTooComplexException;
import io.github.ajdailey.qcaret.core.search.ReplaceTemplate;
import io.github.ajdailey.qcaret.core.search.SearchQuery;
import io.github.ajdailey.qcaret.core.search.TextMatcher;
import java.util.ArrayList;
import java.util.List;
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
 *       visible range, at most {@value #VIEW_MATCHES} ({@link FindHighlights}). A text change
 *       shifts the highlights at once and recomputes them. Esc in a view with one caret and no
 *       block closes the bar.
 *   <li>Replace replaces the focused view's selection when the query matches exactly that range,
 *       then finds the next match. Replace All replaces every match in the document. Each is one
 *       undo step; line breaks in the replacement become the document's line ending.
 *   <li>Find Next, Find Previous and closing the bar save a query that compiles in the search
 *       history HyperSearch also reads; Replace and Replace All save the query and the replacement.
 *       Typing saves nothing, so a partly typed query stays out of the history.
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
  private final FindHighlights highlights;
  private boolean open;
  private boolean replace;
  private String text = "";
  private String replacement = "";
  private String notice = "";
  private boolean error;
  private TextMatcher matcher;
  private CancellationToken finding;
  private long generation;
  private EditorController target;
  private int anchor;
  private boolean disposed;

  /** Runs on the worker before each search; a test seam, null otherwise. */
  private volatile Runnable beforeFind;

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
    this.highlights = new FindHighlights(this, documents, workers, ui);
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
        error,
        settings.current().state().texts(),
        settings.current().state().replacements());
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
    highlights.refresh();
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
    if (matcher != null) {
      remember(false);
    }
    open = false;
    documents.refreshOverview();
    cancelFind();
    highlights.clear();
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
    highlights.refresh();
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

  /** A saved history changed: updates the bar. */
  void historyChanged() {
    if (!disposed) {
      changed();
    }
  }

  /** Puts the search text, and the replacement when asked, first in their saved histories. */
  private void remember(boolean withReplacement) {
    Preferences current = settings.current();
    UiState state = current.state();
    settings.set(
        current.withState(
            state
                .withHistory(
                    UiState.remember(state.texts(), text), state.directories(), state.masks())
                .withReplacements(
                    withReplacement
                        ? UiState.remember(state.replacements(), replacement)
                        : state.replacements())));
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
    remember(false);
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
    remember(true);
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
    remember(true);
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
                report(
                    UiText.FIND_REPLACED.format(
                        UiText.count(
                            replacements.size(), UiText.COUNT_MATCH, UiText.COUNT_MATCHES)),
                    false);
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
    highlights.dispose();
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

  void setBeforeFind(Runnable hook) {
    beforeFind = hook;
  }

  /**
   * Runs a search on a worker, canceling the one before. The result applies on the UI thread while
   * its generation is current; an unchecked failure posts {@link UiText#FIND_FAILED} from {@code
   * finally}, so the bar stops showing the search as running.
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
              String failure = UiText.FIND_FAILED.text();
              boolean canceled = false;
              try {
                Runnable hook = beforeFind;
                if (hook != null) {
                  hook.run();
                }
                apply = task.compute(token);
                failure = null;
              } catch (CancellationException e) {
                canceled = true;
              } catch (PatternTooComplexException e) {
                failure = UiText.FIND_TOO_COMPLEX.text();
              } finally {
                if (!canceled) {
                  post(current, apply, failure);
                }
              }
            }
          });
    } catch (RejectedExecutionException e) {
      finding = null;
    }
  }

  /** Posts a search's result, or its failure when {@code failed} is not null. */
  @WorkerThread
  private void post(final long current, final Runnable result, final String failed) {
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

  /** Reports whether the bar is open. */
  boolean isOpen() {
    return open;
  }

  /** Returns the compiled query, or null when the text is empty or does not compile. */
  TextMatcher matcher() {
    return matcher;
  }

  /** Reports whether the controller is disposed. */
  boolean disposed() {
    return disposed;
  }

  // Highlights: FindHighlights.

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
    highlights.refresh();
    changed();
  }

  @Override
  public void viewportChanged(EditorController editor) {
    if (open && matcher != null && !disposed) {
      highlights.highlight(editor);
    }
  }
}
