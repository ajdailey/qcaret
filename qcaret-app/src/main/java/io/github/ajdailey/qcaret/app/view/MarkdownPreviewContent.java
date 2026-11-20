package io.github.ajdailey.qcaret.app.view;

import io.github.ajdailey.qcaret.app.editor.MarkdownSpans;
import io.github.ajdailey.qcaret.core.markdown.RenderedMarkdown;
import java.util.List;

/**
 * A render to show: the model, the syntax colors of its code blocks, its styles flattened for
 * painting and its local images.
 *
 * @param rendered the render
 * @param codeTokens one entry per {@link RenderedMarkdown#codeBlocks} block, in the same order:
 *     {@code start, end, role} triples in rendered offsets, ascending, the role a {@code TokenRole}
 *     ordinal; empty for a block whose language names no grammar. Shared, not copied: do not modify
 * @param spans the runs and code tokens as {@link MarkdownSpans#of} flattens them. Shared, not
 *     copied: do not modify
 * @param images the local images to decode, in rendered order; empty for Browser content
 * @param html the render as HTML for the Browser preview, or null for Basic content
 */
public record MarkdownPreviewContent(
    RenderedMarkdown rendered,
    List<int[]> codeTokens,
    int[] spans,
    List<MarkdownImageRequest> images,
    MarkdownHtml html) {
  /** Copies the lists. */
  public MarkdownPreviewContent {
    codeTokens = List.copyOf(codeTokens);
    images = List.copyOf(images);
  }

  /**
   * Creates Basic content.
   *
   * @param rendered the render
   * @param codeTokens the code blocks' colors
   * @param spans the flattened styles
   * @param images the local images to decode
   */
  public MarkdownPreviewContent(
      RenderedMarkdown rendered,
      List<int[]> codeTokens,
      int[] spans,
      List<MarkdownImageRequest> images) {
    this(rendered, codeTokens, spans, images, null);
  }
}
