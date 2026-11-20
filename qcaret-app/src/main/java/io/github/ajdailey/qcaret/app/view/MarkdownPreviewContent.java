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
 * @param images the local images to decode, in rendered order
 */
public record MarkdownPreviewContent(
    RenderedMarkdown rendered,
    List<int[]> codeTokens,
    int[] spans,
    List<MarkdownImageRequest> images) {
  /** Copies the lists. */
  public MarkdownPreviewContent {
    codeTokens = List.copyOf(codeTokens);
    images = List.copyOf(images);
  }
}
