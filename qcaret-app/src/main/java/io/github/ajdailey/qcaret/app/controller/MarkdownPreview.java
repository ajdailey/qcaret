package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.view.ActivityIndicator;
import io.github.ajdailey.qcaret.app.view.MarkdownPreviewContent;
import io.github.ajdailey.qcaret.app.view.MarkdownPreviewInput;
import io.github.ajdailey.qcaret.app.view.MarkdownPreviewView;
import io.github.ajdailey.qcaret.app.view.MarkdownViewMode;
import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import io.github.ajdailey.qcaret.core.buffer.TextSnapshot;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.WorkerThread;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.document.Document;
import io.github.ajdailey.qcaret.core.document.DocumentChange;
import io.github.ajdailey.qcaret.core.document.DocumentListener;
import io.github.ajdailey.qcaret.core.markdown.LinkTarget;
import io.github.ajdailey.qcaret.core.markdown.MarkdownFootnote;
import io.github.ajdailey.qcaret.core.markdown.MarkdownLink;
import io.github.ajdailey.qcaret.core.markdown.MarkdownRenderer;
import io.github.ajdailey.qcaret.core.markdown.RenderedMarkdown;
import io.github.ajdailey.qcaret.core.syntax.GrammarSet;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.RejectedExecutionException;

/**
 * One tab's Markdown view mode and preview renders. Confined to the UI thread.
 *
 * <ul>
 *   <li>The mode is Source unless the tab's grammar is {@code markdown}; a grammar change away from
 *       it returns the tab to Source.
 *   <li>Switching from Source to Split or Preview, and {@link #renderRequested}, render on a {@code
 *       qcaret-cpu-} worker under the busy cursor ({@link BusyWork}).
 *   <li>While the mode is not Source, an edit cancels the running render and renders again {@value
 *       #DELAY_MILLIS} ms after the last edit, on a worker without the busy cursor.
 *   <li>A document longer than the size limit is not rendered on a switch or an edit: the view
 *       shows a notice, and {@link #renderRequested} renders it.
 *   <li>A render parses on the worker, then colors the code blocks there ({@link
 *       MarkdownCodeTokens}). It carries a generation and the revision it read; a result whose
 *       generation was overtaken, or whose revision is not the document's, is dropped.
 *   <li>In Split, the editor's and the preview's top lines map through the shown render's source
 *       map; the scroll this class causes is not mapped back.
 *   <li>A click on a link follows it: an anchor scrolls the preview to its heading, a local file
 *       opens in QCaret, a URL opens in the system browser. A click on a footnote reference scrolls
 *       to the footnote.
 * </ul>
 */
final class MarkdownPreview implements MarkdownPreviewInput {
  /** Milliseconds from the last edit to the live render it starts. */
  static final int DELAY_MILLIS = 300;

  /** What a preview needs from its tab and window. Called on the UI thread. */
  interface Tab {
    /** The document's folder, or null for an untitled document. */
    Path folder();

    /** The tab's busy cursor. */
    ActivityIndicator busy();

    /** The grammars code blocks are colored with, or null before they are read. */
    GrammarSet grammars();

    /** The longest code line colored. */
    int maxLineChars();

    /** Scrolls the focused editor so a source line is at the top. */
    void scrollSource(int line);

    /** Opens a local file in QCaret. */
    void openFile(Path path);

    /** Opens a URL in the system browser. */
    void openUrl(String url);
  }

  private final Document document;
  private final Workers workers;
  private final UiDispatcher ui;
  private final MarkdownRenderer renderer;
  private final Tab tab;
  private final int limit;

  private MarkdownPreviewView view;
  private MarkdownViewMode mode = MarkdownViewMode.SOURCE;
  private boolean markdown;
  private boolean disposed;
  private long generation;
  private CancellationToken running;
  private MarkdownPreviewContent shown;
  private int scrolledPreview = -1;
  private int scrolledSource = -1;

  private final Runnable timer =
      new Runnable() {
        @Override
        public void run() {
          render(false, false);
        }
      };

  private final DocumentListener follower =
      new DocumentListener() {
        @Override
        public void textChanged(DocumentChange change) {
          edited();
        }

        @Override
        public void dirtyChanged(boolean dirty) {
          // A render does not depend on the dirty state.
        }
      };

  /**
   * Creates the controller in Source and starts following the document.
   *
   * @param document the tab's document
   * @param workers runs renders
   * @param ui receives results and runs the typing timer
   * @param renderer parses Markdown
   * @param tab the tab's folder, cursor, grammars, editor scroll and link actions
   * @param limit the longest document, in chars, rendered on a switch or an edit
   */
  MarkdownPreview(
      Document document,
      Workers workers,
      UiDispatcher ui,
      MarkdownRenderer renderer,
      Tab tab,
      int limit) {
    this.document = document;
    this.workers = workers;
    this.ui = ui;
    this.renderer = renderer;
    this.tab = tab;
    this.limit = limit;
    document.addListener(follower);
  }

  @Override
  public void attach(MarkdownPreviewView value) {
    view = value;
    if (view != null) {
      view.showMode(mode);
      if (shown != null && mode != MarkdownViewMode.SOURCE) {
        view.showRendered(shown);
      }
    }
  }

  /** Returns the mode. */
  MarkdownViewMode mode() {
    return mode;
  }

  /** Returns the running render's token, or null when none runs. */
  CancellationToken runningRender() {
    return running;
  }

  /** Returns the content the view shows, or null. */
  MarkdownPreviewContent shown() {
    return shown;
  }

  /**
   * Switches the mode; leaving Source renders under the busy cursor.
   *
   * @param value the mode
   * @return false, changing nothing, for Split or Preview on a tab that is not Markdown
   */
  boolean setMode(MarkdownViewMode value) {
    if (disposed || (value != MarkdownViewMode.SOURCE && !markdown)) {
      return false;
    }
    if (value == mode) {
      return true;
    }
    MarkdownViewMode before = mode;
    mode = value;
    if (view != null) {
      view.showMode(value);
    }
    if (value == MarkdownViewMode.SOURCE) {
      stop();
    } else if (before == MarkdownViewMode.SOURCE) {
      render(true, false);
    }
    return true;
  }

  /**
   * Follows the tab's grammar.
   *
   * @param isMarkdown true when the grammar is {@code markdown}
   */
  void grammarChanged(boolean isMarkdown) {
    markdown = isMarkdown;
    if (!isMarkdown) {
      setMode(MarkdownViewMode.SOURCE);
    }
  }

  @Override
  public void renderRequested() {
    if (!disposed && mode != MarkdownViewMode.SOURCE) {
      render(true, true);
    }
  }

  @Override
  public void previewScrolled(int line) {
    if (mode != MarkdownViewMode.SPLIT || shown == null) {
      return;
    }
    if (line == scrolledPreview) {
      scrolledPreview = -1;
      return;
    }
    scrolledSource = shown.rendered().sourceLine(line);
    tab.scrollSource(scrolledSource);
  }

  /**
   * Reports the editor's new top line; in Split the preview scrolls to the rendered line showing
   * it.
   *
   * @param line the source line at the top
   */
  void sourceScrolled(int line) {
    if (mode != MarkdownViewMode.SPLIT || shown == null) {
      return;
    }
    if (line == scrolledSource) {
      scrolledSource = -1;
      return;
    }
    scrolledPreview = shown.rendered().renderedLine(line);
    if (view != null) {
      view.scrollToLine(scrolledPreview);
    }
  }

  @Override
  public void clicked(int offset) {
    if (shown == null || mode == MarkdownViewMode.SOURCE) {
      return;
    }
    RenderedMarkdown r = shown.rendered();
    for (MarkdownFootnote.Reference ref : r.footnoteReferences()) {
      if (offset >= ref.start() && offset < ref.end()) {
        for (MarkdownFootnote note : r.footnotes()) {
          if (note.number() == ref.number()) {
            scrollPreview(note.line());
          }
        }
        return;
      }
    }
    MarkdownLink link = linkAt(r.links(), offset);
    if (link == null) {
      return;
    }
    LinkTarget target = link.target();
    switch (target.kind()) {
      case ANCHOR -> {
        int line = target.anchor().isEmpty() ? 0 : r.headingLine(target.anchor());
        if (line >= 0) {
          scrollPreview(line);
        }
      }
      case FILE -> {
        if (target.path() != null) {
          tab.openFile(target.path());
        }
      }
      default -> tab.openUrl(target.destination());
    }
  }

  /** The innermost link holding an offset: the last one starting at or before it. */
  private static MarkdownLink linkAt(List<MarkdownLink> links, int offset) {
    MarkdownLink found = null;
    for (MarkdownLink link : links) {
      if (link.start() <= offset && offset < link.end()) {
        found = link;
      }
    }
    return found;
  }

  private void scrollPreview(int line) {
    if (view != null) {
      view.scrollToLine(line);
    }
  }

  /** Cancels the running render and the timer and stops following the document. */
  void dispose() {
    disposed = true;
    stop();
    document.removeListener(follower);
    view = null;
  }

  private void edited() {
    if (mode == MarkdownViewMode.SOURCE) {
      return;
    }
    cancelRunning();
    ui.cancelTimer(timer);
    ui.timerExec(DELAY_MILLIS, timer);
  }

  private void stop() {
    cancelRunning();
    ui.cancelTimer(timer);
  }

  /** Overtakes every render started so far and cancels the running one. */
  private void cancelRunning() {
    generation++;
    if (running != null) {
      running.cancel();
      running = null;
    }
  }

  /**
   * Renders the current revision on a worker.
   *
   * @param user true for a switch or a request: under the busy cursor
   * @param force true to render above the size limit
   */
  private void render(boolean user, boolean force) {
    if (disposed || mode == MarkdownViewMode.SOURCE) {
      return;
    }
    TextSnapshot text = document.text();
    if (!force && text.length() > limit) {
      if (view != null) {
        view.showSizeNotice(text.length(), limit);
      }
      return;
    }
    cancelRunning();
    running = new CancellationToken();
    final Job job =
        new Job(
            generation,
            document.revision(),
            text,
            renderer,
            tab.folder(),
            tab.grammars(),
            tab.maxLineChars(),
            running);
    if (user) {
      boolean started =
          new BusyWork(ui, tab.busy()) {
            @Override
            @WorkerThread
            void work() {
              job.run();
            }

            @Override
            void finished(boolean completed) {
              apply(job);
            }
          }.startCpu(workers);
      if (!started) {
        running = null;
      }
      return;
    }
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
        || job.result == null
        || job.generation != generation
        || job.revision != document.revision()
        || mode == MarkdownViewMode.SOURCE) {
      return;
    }
    shown = job.result;
    if (view != null) {
      view.showRendered(shown);
    }
  }

  /** One render: what it reads, and its result once {@link #run} returns. */
  private static final class Job {
    final long generation;
    final long revision;
    final TextSnapshot text;
    final MarkdownRenderer renderer;
    final Path folder;
    final GrammarSet grammars;
    final int maxLineChars;
    final CancellationToken cancel;
    volatile MarkdownPreviewContent result;

    Job(
        long generation,
        long revision,
        TextSnapshot text,
        MarkdownRenderer renderer,
        Path folder,
        GrammarSet grammars,
        int maxLineChars,
        CancellationToken cancel) {
      this.generation = generation;
      this.revision = revision;
      this.text = text;
      this.renderer = renderer;
      this.folder = folder;
      this.grammars = grammars;
      this.maxLineChars = maxLineChars;
      this.cancel = cancel;
    }

    @WorkerThread
    void run() {
      try {
        RenderedMarkdown rendered = renderer.render(text, folder, cancel);
        result =
            new MarkdownPreviewContent(
                rendered, MarkdownCodeTokens.of(rendered, grammars, maxLineChars, cancel));
      } catch (CancellationException e) {
        result = null;
      }
    }
  }
}
