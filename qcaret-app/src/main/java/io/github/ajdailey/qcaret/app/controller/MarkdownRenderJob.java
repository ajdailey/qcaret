package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.editor.MarkdownSpans;
import io.github.ajdailey.qcaret.app.view.MarkdownHtml;
import io.github.ajdailey.qcaret.app.view.MarkdownImageRequest;
import io.github.ajdailey.qcaret.app.view.MarkdownPreviewContent;
import io.github.ajdailey.qcaret.core.buffer.TextSnapshot;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.WorkerThread;
import io.github.ajdailey.qcaret.core.markdown.MarkdownRenderer;
import io.github.ajdailey.qcaret.core.markdown.MarkdownTree;
import io.github.ajdailey.qcaret.core.markdown.RenderedMarkdown;
import io.github.ajdailey.qcaret.core.syntax.GrammarSet;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CancellationException;

/**
 * One preview render: what it reads, and its result once {@link #run} returns. The text parses
 * once; Basic content takes the render, its code colors, spans and image requests, and Browser
 * content adds the HTML and leaves image decoding to the browser.
 */
final class MarkdownRenderJob {
  final long generation;
  final long revision;
  final CancellationToken cancel;
  final long charge;
  private final TextSnapshot text;
  private final MarkdownRenderer renderer;
  private final Path folder;
  private final GrammarSet grammars;
  private final int maxLineChars;
  private final boolean browser;
  private volatile MarkdownPreviewContent result;

  MarkdownRenderJob(
      long generation,
      long revision,
      TextSnapshot text,
      MarkdownRenderer renderer,
      Path folder,
      GrammarSet grammars,
      int maxLineChars,
      boolean browser,
      CancellationToken cancel,
      long charge) {
    this.generation = generation;
    this.revision = revision;
    this.text = text;
    this.renderer = renderer;
    this.folder = folder;
    this.grammars = grammars;
    this.maxLineChars = maxLineChars;
    this.browser = browser;
    this.cancel = cancel;
    this.charge = charge;
  }

  /** Returns the content, or null before {@link #run} returned or after a cancel. */
  MarkdownPreviewContent result() {
    return result;
  }

  @WorkerThread
  void run() {
    try {
      MarkdownTree tree = renderer.parse(text, cancel);
      RenderedMarkdown rendered = renderer.render(tree, folder, cancel);
      List<int[]> tokens = MarkdownCodeTokens.of(rendered, grammars, maxLineChars, cancel);
      MarkdownHtml html =
          browser ? MarkdownHtmlWriter.preview(tree, folder, grammars, maxLineChars, cancel) : null;
      List<MarkdownImageRequest> images =
          browser ? List.<MarkdownImageRequest>of() : MarkdownImages.requests(rendered);
      cancel.check();
      result =
          new MarkdownPreviewContent(
              rendered, tokens, MarkdownSpans.of(rendered, tokens), images, html);
    } catch (CancellationException e) {
      result = null;
    }
  }
}
