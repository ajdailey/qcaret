package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.editor.MarkdownSpans;
import io.github.ajdailey.qcaret.app.print.MarkdownPageLayout;
import io.github.ajdailey.qcaret.app.print.MarkdownPrintSource;
import io.github.ajdailey.qcaret.app.view.MarkdownImageRequest;
import io.github.ajdailey.qcaret.app.view.MarkdownPreviewContent;
import io.github.ajdailey.qcaret.app.view.MarkdownPrintSheet;
import io.github.ajdailey.qcaret.app.view.PrintTarget;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.WorkerThread;
import io.github.ajdailey.qcaret.core.markdown.MarkdownRenderer;
import io.github.ajdailey.qcaret.core.markdown.RenderedMarkdown;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * The pages of File > Print Rendered Markdown, built on the job's thread: the source rendered as
 * the Basic preview renders it, code blocks colored, local images read, the pages laid out by
 * {@link MarkdownPageLayout}. Image files follow the preview's rules: an SVG, a file over {@link
 * MarkdownImages#MAX_FILE_BYTES} and a file that cannot be read print as a placeholder.
 */
final class MarkdownPrintJob implements PrintController.Pages {
  private final MarkdownPrintSheet sheet;
  private final MarkdownPageLayout layout;

  private MarkdownPrintJob(MarkdownPrintSheet sheet, MarkdownPageLayout layout) {
    this.sheet = sheet;
    this.layout = layout;
  }

  /**
   * Renders a source, starts the job and lays out its pages.
   *
   * @param source the document
   * @param target the job
   * @param cancel checked per block, image and line
   * @return the pages
   * @throws java.util.concurrent.CancellationException when canceled
   */
  @WorkerThread
  static MarkdownPrintJob start(
      MarkdownPrintSource source, PrintTarget target, CancellationToken cancel) {
    RenderedMarkdown rendered =
        new MarkdownRenderer().render(source.text(), source.folder(), cancel);
    List<int[]> tokens =
        MarkdownCodeTokens.of(rendered, source.grammars(), source.maxLineChars(), cancel);
    List<MarkdownImageRequest> images = MarkdownImages.requests(rendered);
    MarkdownPreviewContent content =
        new MarkdownPreviewContent(rendered, tokens, MarkdownSpans.of(rendered, tokens), images);
    cancel.check();
    MarkdownPrintSheet sheet = target.startMarkdown(source.name(), content);
    for (MarkdownImageRequest image : images) {
      cancel.check();
      sheet.image(image, read(image.path()));
    }
    return new MarkdownPrintJob(sheet, MarkdownPageLayout.of(rendered, sheet, cancel));
  }

  /** Returns an image file's bytes, empty for one the preview does not decode. */
  @WorkerThread
  private static byte[] read(Path path) {
    Path file = path.getFileName();
    if (file == null || file.toString().toLowerCase(Locale.ROOT).endsWith(".svg")) {
      return new byte[0];
    }
    try {
      return Files.size(path) > MarkdownImages.MAX_FILE_BYTES
          ? new byte[0]
          : Files.readAllBytes(path);
    } catch (IOException e) {
      return new byte[0];
    }
  }

  @Override
  public int count() {
    return layout.pageCount();
  }

  @Override
  @WorkerThread
  public void print(int number, CancellationToken cancel) {
    cancel.check();
    sheet.page(layout.page(number));
  }
}
