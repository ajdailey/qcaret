package io.github.ajdailey.qcaret.app.view;

import io.github.ajdailey.qcaret.core.markdown.RenderedMarkdown;
import java.util.List;

/**
 * A render to show: the model and the syntax colors of its code blocks.
 *
 * @param rendered the render
 * @param codeTokens one entry per {@link RenderedMarkdown#codeBlocks} block, in the same order:
 *     {@code start, end, role} triples in rendered offsets, ascending, the role a {@code TokenRole}
 *     ordinal; empty for a block whose language names no grammar. Shared, not copied: do not modify
 */
public record MarkdownPreviewContent(RenderedMarkdown rendered, List<int[]> codeTokens) {
  /** Copies the list. */
  public MarkdownPreviewContent {
    codeTokens = List.copyOf(codeTokens);
  }
}
