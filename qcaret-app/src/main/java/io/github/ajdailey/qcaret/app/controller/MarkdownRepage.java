package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.editor.MarkdownPage;
import io.github.ajdailey.qcaret.app.theme.Palette;
import io.github.ajdailey.qcaret.app.view.MarkdownPreviewContent;
import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import io.github.ajdailey.qcaret.core.concurrent.WorkerThread;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import java.util.concurrent.RejectedExecutionException;

/**
 * Rebuilds a Browser render's page in another palette on a {@code qcaret-cpu-} worker, for a theme
 * switch. Each {@link #start} takes a new generation; a page whose generation was overtaken by a
 * later {@link #start} or {@link #cancel} is dropped. Confined to the UI thread.
 */
final class MarkdownRepage {
  /** Receives a rebuilt page on the UI thread. */
  interface Target {
    /**
     * A page was built for the newest request.
     *
     * @param from the content the request named
     * @param next {@code from} with the new page
     */
    void built(MarkdownPreviewContent from, MarkdownPreviewContent next);
  }

  private final Workers workers;
  private final UiDispatcher ui;
  private final Target target;
  private long generation;

  MarkdownRepage(Workers workers, UiDispatcher ui, Target target) {
    this.workers = workers;
    this.ui = ui;
    this.target = target;
  }

  /**
   * Builds the page of Browser content in a palette.
   *
   * @param from content with HTML
   * @param palette the palette
   */
  void start(final MarkdownPreviewContent from, final Palette palette) {
    final long started = ++generation;
    try {
      workers.executeCpu(
          new Runnable() {
            @Override
            @WorkerThread
            public void run() {
              final MarkdownPreviewContent next = repaged(from, palette);
              ui.asyncExec(
                  new Runnable() {
                    @Override
                    public void run() {
                      if (started == generation) {
                        target.built(from, next);
                      }
                    }
                  });
            }
          });
    } catch (RejectedExecutionException e) {
      // The workers are closing with the window.
    }
  }

  /** Drops the page being built. */
  void cancel() {
    generation++;
  }

  /** Returns content with its page built in a palette. */
  private static MarkdownPreviewContent repaged(MarkdownPreviewContent from, Palette palette) {
    String page = MarkdownPage.page("", from.html().body(), palette);
    return new MarkdownPreviewContent(
        from.rendered(),
        from.codeTokens(),
        from.spans(),
        from.images(),
        from.html().withPage(page));
  }
}
