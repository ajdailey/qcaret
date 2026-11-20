package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.view.EditorView;
import io.github.ajdailey.qcaret.app.view.MainView;
import io.github.ajdailey.qcaret.app.view.OutlineEntry;
import io.github.ajdailey.qcaret.app.view.OutlineInput;
import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import io.github.ajdailey.qcaret.core.buffer.TextSnapshot;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.WorkerThread;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.document.Document;
import io.github.ajdailey.qcaret.core.document.DocumentChange;
import io.github.ajdailey.qcaret.core.document.DocumentListener;
import io.github.ajdailey.qcaret.core.document.SelectionListener;
import io.github.ajdailey.qcaret.core.document.SelectionModel;
import io.github.ajdailey.qcaret.core.markdown.MarkdownHeading;
import io.github.ajdailey.qcaret.core.markdown.MarkdownRenderer;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.RejectedExecutionException;

/**
 * The Outline dock's model: the headings of the selected tab while its grammar is {@code markdown},
 * as a tree ({@link OutlineTree}). It follows the selected tab, its grammar and its focused view.
 * Nothing is rendered while the dock is hidden. Confined to the UI thread.
 *
 * <p>The outline renders on its own, in every view mode, so Source lists headings too: on binding a
 * Markdown tab at once, and {@value #DELAY_MILLIS} ms after the last edit, on a {@code qcaret-cpu-}
 * worker from a text snapshot, without the busy cursor. A render carries a generation and the
 * revision it read; one overtaken by an edit or a rebind, or read at another revision, is dropped.
 * A document over {@link MarkdownPreviews#LIVE_LIMIT_CHARS} chars is not rendered and lists no
 * entries. The selected entry follows the focused editor's caret.
 */
public final class MarkdownOutline implements OutlineInput {
  /** Milliseconds from the last edit to the render it starts, as the live preview. */
  static final int DELAY_MILLIS = MarkdownPreview.DELAY_MILLIS;

  private final DocumentController documents;
  private final Workers workers;
  private final UiDispatcher ui;
  private final MainView view;
  private final MarkdownRenderer renderer = new MarkdownRenderer();
  private final int limit;

  private final Runnable timer =
      new Runnable() {
        @Override
        public void run() {
          render();
        }
      };
  private final Runnable activeChanged =
      new Runnable() {
        @Override
        public void run() {
          bind();
        }
      };
  private final DocumentListener edits =
      new DocumentListener() {
        @Override
        public void textChanged(DocumentChange change) {
          edited();
        }

        @Override
        public void dirtyChanged(boolean dirty) {
          // Headings follow the text only.
        }
      };
  private final SelectionListener carets =
      new SelectionListener() {
        @Override
        public void selectionsChanged(SelectionModel model) {
          follow();
        }
      };

  private boolean shown;
  private boolean disposed;
  private Document document;
  private EditorController editor;
  private boolean markdown;
  private boolean tooLarge;
  private List<OutlineEntry> entries = List.of();
  private int total;
  private int selected = -1;
  private long generation;
  private CancellationToken running;

  MarkdownOutline(DocumentController documents, Workers workers, UiDispatcher ui, MainView view) {
    this(documents, workers, ui, view, MarkdownPreviews.LIVE_LIMIT_CHARS);
  }

  /**
   * Creates the outline with a size limit.
   *
   * @param limit the longest document, in chars, rendered
   */
  MarkdownOutline(
      DocumentController documents, Workers workers, UiDispatcher ui, MainView view, int limit) {
    this.documents = documents;
    this.workers = workers;
    this.ui = ui;
    this.view = view;
    this.limit = limit;
  }

  /**
   * Reports whether the dock shows.
   *
   * @return true while shown
   */
  public boolean shown() {
    return shown;
  }

  /** Shows the dock, or hides it. */
  void toggle() {
    setShown(!shown);
  }

  /** Shows the dock when a session saved it shown. */
  void restore(boolean show) {
    setShown(show);
  }

  private void setShown(boolean value) {
    if (disposed || value == shown) {
      return;
    }
    shown = value;
    if (shown) {
      view.showOutline(this, true);
      documents.addActiveListener(activeChanged);
      documents.setGrammarListener(activeChanged);
      bind();
    } else {
      documents.removeActiveListener(activeChanged);
      documents.setGrammarListener(null);
      unbind();
      view.showOutline(this, false);
    }
    view.documentStateChanged();
    documents.sessionChanged();
  }

  /** Stops following the documents. */
  void dispose() {
    if (shown) {
      documents.removeActiveListener(activeChanged);
      documents.setGrammarListener(null);
    }
    disposed = true;
    shown = false;
    unbind();
  }

  /** Follows the selected tab, its grammar and its focused view. */
  private void bind() {
    Document nextDocument = documents.activeDocument();
    boolean nextMarkdown = nextDocument != null && documents.activeMarkdown();
    EditorController nextEditor = nextDocument == null ? null : documents.activeEditor();
    if (nextEditor != editor) {
      if (editor != null) {
        editor.selections().removeListener(carets);
      }
      editor = nextEditor;
      if (editor != null) {
        editor.selections().addListener(carets);
      }
    }
    if (nextDocument == document && nextMarkdown == markdown) {
      follow();
      return;
    }
    stop();
    if (document != null) {
      document.removeListener(edits);
    }
    document = nextDocument;
    markdown = nextMarkdown;
    if (document != null) {
      document.addListener(edits);
    }
    show(List.<OutlineEntry>of(), 0);
    if (markdown) {
      render();
    }
  }

  private void unbind() {
    stop();
    if (editor != null) {
      editor.selections().removeListener(carets);
    }
    if (document != null) {
      document.removeListener(edits);
    }
    editor = null;
    document = null;
    markdown = false;
    entries = List.of();
    total = 0;
    selected = -1;
    tooLarge = false;
  }

  /** Overtakes every render started so far and cancels the running one and the timer. */
  private void stop() {
    generation++;
    ui.cancelTimer(timer);
    if (running != null) {
      running.cancel();
      running = null;
    }
  }

  private void edited() {
    if (disposed || !markdown) {
      return;
    }
    stop();
    ui.timerExec(DELAY_MILLIS, timer);
  }

  private void render() {
    if (disposed || document == null || !markdown) {
      return;
    }
    stop();
    TextSnapshot text = document.text();
    tooLarge = text.length() > limit;
    if (tooLarge) {
      show(List.<OutlineEntry>of(), 0);
      return;
    }
    final Job job =
        new Job(generation, document.revision(), text, renderer, new CancellationToken());
    running = job.cancel;
    try {
      workers.executeCpu(
          new Runnable() {
            @Override
            public void run() {
              try {
                job.run();
              } finally {
                ui.asyncExec(
                    new Runnable() {
                      @Override
                      public void run() {
                        apply(job);
                      }
                    });
              }
            }
          });
    } catch (RejectedExecutionException e) {
      running = null;
    }
  }

  /** Shows a finished render unless it was overtaken. */
  private void apply(Job job) {
    if (running == job.cancel) {
      running = null;
    }
    if (disposed
        || job.entries == null
        || job.generation != generation
        || document == null
        || job.revision != document.revision()) {
      return;
    }
    show(job.entries, job.total);
  }

  private void show(List<OutlineEntry> next, int count) {
    entries = next;
    total = count;
    selected = caretEntry();
    view.outlineChanged();
  }

  /** Selects the entry holding the caret when it changed. */
  private void follow() {
    int next = caretEntry();
    if (next != selected) {
      selected = next;
      view.outlineSelected(next);
    }
  }

  private int caretEntry() {
    if (editor == null || entries.isEmpty()) {
      return -1;
    }
    TextSnapshot text = editor.document().text();
    int caret = editor.selections().selections().primary().caret();
    return OutlineTree.at(entries, text.lineAt(Math.min(caret, text.length())));
  }

  /** Returns the running render's token, or null when none runs. */
  CancellationToken runningRender() {
    return running;
  }

  // OutlineInput.

  @Override
  public List<OutlineEntry> entries() {
    return entries;
  }

  @Override
  public String summary() {
    if (document == null || !markdown) {
      return UiText.OUTLINE_NOT_MARKDOWN.text();
    }
    String name = documents.nameOf(document);
    if (tooLarge) {
      return UiText.OUTLINE_TOO_LARGE.format(name, limit);
    }
    return entries.size() < total
        ? UiText.OUTLINE_SUMMARY_CUT.format(name, entries.size(), total)
        : UiText.OUTLINE_SUMMARY.format(
            name, UiText.count(total, UiText.COUNT_HEADING, UiText.COUNT_HEADINGS));
  }

  @Override
  public int selected() {
    return selected;
  }

  @Override
  public void goTo(int index, boolean focus) {
    if (index < 0 || index >= entries.size() || editor == null) {
      return;
    }
    int line = Math.min(entries.get(index).sourceLine(), editor.document().text().lineCount() - 1);
    editor.goTo(line, 0);
    EditorView pane = editor.view();
    if (pane != null) {
      pane.scrollToRow(editor.projection().rowOfLine(line));
    }
    documents.revealMarkdownSource(line);
    if (focus && pane != null) {
      pane.focus();
    }
  }

  @Override
  public void hide() {
    setShown(false);
  }

  /** One render: what it reads, and its entries once {@link #run} returns. */
  private static final class Job {
    final long generation;
    final long revision;
    final TextSnapshot text;
    final MarkdownRenderer renderer;
    final CancellationToken cancel;
    volatile List<OutlineEntry> entries;
    volatile int total;

    Job(
        long generation,
        long revision,
        TextSnapshot text,
        MarkdownRenderer renderer,
        CancellationToken cancel) {
      this.generation = generation;
      this.revision = revision;
      this.text = text;
      this.renderer = renderer;
      this.cancel = cancel;
    }

    @WorkerThread
    void run() {
      try {
        List<MarkdownHeading> headings = renderer.render(text, null, cancel).headings();
        total = headings.size();
        entries = OutlineTree.of(headings);
      } catch (CancellationException e) {
        entries = null;
      }
    }
  }
}
