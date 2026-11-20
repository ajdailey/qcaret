package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.theme.Palette;
import io.github.ajdailey.qcaret.app.view.ActivityIndicator;
import io.github.ajdailey.qcaret.app.view.MarkdownPreviewContent;
import io.github.ajdailey.qcaret.app.view.MarkdownPreviewInput;
import io.github.ajdailey.qcaret.app.view.MarkdownPreviewView;
import io.github.ajdailey.qcaret.app.view.MarkdownViewMode;
import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import io.github.ajdailey.qcaret.core.buffer.TextSnapshot;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.concurrent.WorkerThread;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.document.Document;
import io.github.ajdailey.qcaret.core.document.DocumentChange;
import io.github.ajdailey.qcaret.core.document.DocumentListener;
import io.github.ajdailey.qcaret.core.markdown.MarkdownRenderer;
import io.github.ajdailey.qcaret.core.syntax.GrammarSet;
import java.nio.file.Path;
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
 *       shows a notice, and {@link #renderRequested} renders it, charged to the {@link
 *       MemoryBudget} through {@link MarkdownCharge}; a refused charge renders nothing and posts a
 *       notice.
 *   <li>A render runs as a {@link MarkdownRenderJob}, with HTML and its page in {@link Tab#palette}
 *       while {@link Tab#browser} holds. It carries a generation and the revision it read; a result
 *       whose generation was overtaken, or whose revision is not the document's, is dropped. {@link
 *       #rendererChanged} and {@link #browserFailed} render again under the busy cursor.
 *   <li>{@link #paletteChanged}, and a Browser result built in a palette since replaced, rebuild
 *       the page on a worker through {@link MarkdownRepage}; a page built for content no longer
 *       shown is dropped.
 *   <li>A shown render's local images decode through {@link MarkdownImages}; the next render, a
 *       switch to Source and a close cancel the decode.
 *   <li>In Split, the editor's and the preview's top lines map through the shown render's source
 *       map; the scroll this class causes is not mapped back.
 *   <li>A restored mode follows {@link MarkdownRestore}.
 *   <li>A reload of the grammars renders again, so code blocks take the new grammars.
 *   <li>A click on a link follows it: an anchor scrolls the preview to its heading, a local file
 *       opens in QCaret, a URL opens in the system browser. A click on a footnote reference scrolls
 *       to the footnote. The Browser preview reports its links through {@link #linkFollowed}.
 * </ul>
 */
final class MarkdownPreview implements MarkdownPreviewInput {
  /** Milliseconds from the last edit to the live render it starts. */
  static final int DELAY_MILLIS = 300;

  /** {@link #scrolledSource} while the editor scrolls for the preview. */
  private static final int SCROLLING = -2;

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

    /**
     * Scrolls the focused editor so a source line is at the top.
     *
     * @param line the source line
     * @return the source line at the top after the scroll, which the end of the document can hold
     *     above {@code line}
     */
    int scrollSource(int line);

    /** Shows a message in the window's status bar. */
    void notice(String message);

    /** Opens a local file in QCaret. */
    void openFile(Path path);

    /** Opens a URL in the system browser. */
    void openUrl(String url);

    /** Reports whether renders build the Browser preview's HTML. */
    boolean browser();

    /** The palette of the Browser preview's page. */
    Palette palette();

    /** Posts the Browser preview's failure: the notice and the renderer shown. */
    void browserFailed();
  }

  private final Document document;
  private final Workers workers;
  private final UiDispatcher ui;
  private final MarkdownRenderer renderer;
  private final Tab tab;
  private final int limit;
  private final MarkdownCharge charge;
  private final MarkdownImages images;
  private final MarkdownRestore restore = new MarkdownRestore();
  private final MarkdownRepage repage;

  private MarkdownPreviewView view;
  private MarkdownViewMode mode = MarkdownViewMode.SOURCE;
  private boolean markdown;
  private boolean disposed;
  private long generation;
  private CancellationToken running;
  private MarkdownPreviewContent shown;
  private int scrolledPreview = -1;
  private int scrolledSource = -1;
  private CancellationToken decoding;

  private final MarkdownLinks.Scroller scroller =
      new MarkdownLinks.Scroller() {
        @Override
        public void toLine(int line) {
          scrollPreview(line);
        }

        @Override
        public void toAnchor(String id) {
          if (view != null) {
            view.scrollToAnchor(id);
          }
        }
      };

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
   * @param budget charged for a render above the limit
   * @param images decodes the shown render's images
   */
  MarkdownPreview(
      Document document,
      Workers workers,
      UiDispatcher ui,
      MarkdownRenderer renderer,
      Tab tab,
      int limit,
      MemoryBudget budget,
      MarkdownImages images) {
    this.document = document;
    this.workers = workers;
    this.ui = ui;
    this.renderer = renderer;
    this.tab = tab;
    this.limit = limit;
    this.charge = new MarkdownCharge(budget, limit);
    this.images = images;
    this.repage =
        new MarkdownRepage(
            workers,
            ui,
            new MarkdownRepage.Target() {
              @Override
              public void built(MarkdownPreviewContent from, MarkdownPreviewContent next) {
                if (!disposed && shown == from && mode != MarkdownViewMode.SOURCE) {
                  shown = next;
                  if (view != null) {
                    view.showRendered(next);
                  }
                }
              }
            });
    document.addListener(follower);
  }

  @Override
  public void attach(MarkdownPreviewView value) {
    view = value;
    if (view != null) {
      view.showMode(mode);
      if (shown != null && mode != MarkdownViewMode.SOURCE) {
        view.showRendered(shown);
        decode();
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
    MarkdownViewMode restored = restore.grammarKnown();
    if (!isMarkdown) {
      setMode(MarkdownViewMode.SOURCE);
    } else if (restored != null) {
      setMode(restored);
    }
  }

  /**
   * Applies a mode saved with the tab's file.
   *
   * @param value the saved mode
   * @param detecting true while the tab's grammar is not known yet: the mode waits for the next
   *     {@link #grammarChanged}
   */
  void restoreMode(MarkdownViewMode value, boolean detecting) {
    MarkdownViewMode now = restore.restore(value, markdown, detecting);
    if (now != null) {
      setMode(now);
    }
  }

  /** Renders again with reloaded grammars while the preview shows. */
  void grammarsReloaded() {
    if (!disposed && mode != MarkdownViewMode.SOURCE) {
      render(false, false);
    }
  }

  /** Renders again under the busy cursor with the renderer now chosen, while the preview shows. */
  void rendererChanged() {
    if (!disposed && mode != MarkdownViewMode.SOURCE) {
      render(true, false);
    }
  }

  /** Rebuilds the shown Browser page in {@link Tab#palette} on a worker. */
  void paletteChanged() {
    if (!disposed && mode != MarkdownViewMode.SOURCE && shown != null && shown.html() != null) {
      repage.start(shown, tab.palette());
    }
  }

  @Override
  public void browserFailed() {
    if (!disposed) {
      tab.browserFailed();
      rendererChanged();
    }
  }

  @Override
  public void linkFollowed(String location) {
    if (shown != null && mode != MarkdownViewMode.SOURCE && location != null) {
      MarkdownLinks.followed(location, tab, scroller);
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
    scrolledSource = SCROLLING;
    scrolledSource = tab.scrollSource(shown.rendered().sourceLine(line));
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
    if (line == scrolledSource || scrolledSource == SCROLLING) {
      scrolledSource = -1;
      return;
    }
    scrolledPreview = shown.rendered().renderedLine(line);
    if (view != null) {
      view.scrollToLine(scrolledPreview);
    }
  }

  /**
   * Scrolls the preview to the rendered line showing a source line, outside Source and once a
   * render shows; the scroll is not mapped back to the editor.
   *
   * @param line a zero-based source line
   */
  void revealSource(int line) {
    if (mode == MarkdownViewMode.SOURCE || shown == null) {
      return;
    }
    scrolledPreview = shown.rendered().renderedLine(line);
    scrollPreview(scrolledPreview);
  }

  @Override
  public void clicked(int offset) {
    if (shown != null && mode != MarkdownViewMode.SOURCE) {
      MarkdownLinks.clicked(shown.rendered(), offset, tab, scroller);
    }
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
    shown = null;
    repage.cancel();
    charge.dispose();
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
    cancelDecode();
  }

  private void cancelDecode() {
    if (decoding != null) {
      decoding.cancel();
      decoding = null;
    }
  }

  /** Starts decoding the shown render's images. */
  private void decode() {
    cancelDecode();
    if (view != null && shown != null) {
      decoding = images.show(shown.images(), view);
    }
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
    final long reserved = charge.reserve(text.length());
    if (reserved < 0) {
      tab.notice(UiText.MARKDOWN_NO_MEMORY.text());
      return;
    }
    cancelRunning();
    running = new CancellationToken();
    final MarkdownRenderJob job =
        new MarkdownRenderJob(
            generation,
            document.revision(),
            text,
            renderer,
            tab.folder(),
            tab.grammars(),
            tab.maxLineChars(),
            tab.browser() ? tab.palette() : null,
            running,
            reserved);
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
        charge.release(reserved);
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
      charge.release(reserved);
    }
  }

  /** Shows a finished render unless it was overtaken. */
  private void apply(MarkdownRenderJob job) {
    if (running == job.cancel) {
      running = null;
    }
    if (disposed
        || job.result() == null
        || job.generation != generation
        || job.revision != document.revision()
        || mode == MarkdownViewMode.SOURCE) {
      charge.release(job.charge);
      return;
    }
    shown = job.result();
    charge.shown(job.charge);
    repage.cancel();
    if (job.palette != null && !job.palette.equals(tab.palette())) {
      paletteChanged();
    } else if (view != null) {
      view.showRendered(shown);
      decode();
    }
  }
}
