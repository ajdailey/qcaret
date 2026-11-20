package io.github.ajdailey.qcaret.core.markdown;

import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.WorkerThread;
import java.nio.file.Path;
import java.util.List;
import org.commonmark.ext.autolink.AutolinkExtension;
import org.commonmark.ext.footnotes.FootnotesExtension;
import org.commonmark.ext.front.matter.YamlFrontMatterExtension;
import org.commonmark.ext.gfm.alerts.AlertsExtension;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.ext.image.attributes.ImageAttributesExtension;
import org.commonmark.ext.task.list.items.TaskListItemsExtension;
import org.commonmark.node.Node;
import org.commonmark.parser.IncludeSourceSpans;
import org.commonmark.parser.Parser;

/**
 * Parses Markdown once with commonmark, the GFM table, strikethrough, task list, autolink,
 * footnote, alert, YAML front matter and image attribute extensions and GitLab's {@code >>>} quotes
 * and {@code [[_TOC_]]}, and builds a {@link RenderedMarkdown}. Thread-safe: each {@link #render}
 * uses its own state.
 *
 * <p>The parse itself cannot be canceled; the token is checked after it and before every block.
 * Rendering reads no file.
 */
public final class MarkdownRenderer {
  private final Parser parser;

  /** Creates a renderer. */
  public MarkdownRenderer() {
    this.parser =
        Parser.builder()
            .extensions(
                List.of(
                    TablesExtension.create(),
                    StrikethroughExtension.create(),
                    TaskListItemsExtension.create(),
                    AutolinkExtension.create(),
                    FootnotesExtension.create(),
                    AlertsExtension.builder().allowCustomTitles(true).build(),
                    YamlFrontMatterExtension.create(),
                    ImageAttributesExtension.create()))
            .customBlockParserFactory(GitLabBlocks.FACTORY)
            .includeSourceSpans(IncludeSourceSpans.BLOCKS)
            .build();
  }

  /**
   * Renders a text.
   *
   * @param text the Markdown source; LF, CR LF and CR breaks
   * @param folder the document's folder, against which relative link and image paths resolve; null
   *     for an untitled document
   * @param cancel checked between blocks
   * @return the render
   * @throws java.util.concurrent.CancellationException when canceled
   */
  @WorkerThread
  public RenderedMarkdown render(CharSequence text, Path folder, CancellationToken cancel) {
    return render(parse(text, cancel), folder, cancel);
  }

  /**
   * Parses a text without rendering it.
   *
   * @param text the Markdown source; LF, CR LF and CR breaks
   * @param cancel checked before and after the parse
   * @return the parsed document
   * @throws java.util.concurrent.CancellationException when canceled
   */
  @WorkerThread
  public MarkdownTree parse(CharSequence text, CancellationToken cancel) {
    cancel.check();
    String source = text.toString();
    Node document = parser.parse(source);
    cancel.check();
    return new MarkdownTree(source, document);
  }

  /**
   * Renders a parsed document.
   *
   * @param tree a document {@link #parse} returned
   * @param folder the document's folder, against which relative link and image paths resolve; null
   *     for an untitled document
   * @param cancel checked between blocks
   * @return the render
   * @throws java.util.concurrent.CancellationException when canceled
   */
  @WorkerThread
  public RenderedMarkdown render(MarkdownTree tree, Path folder, CancellationToken cancel) {
    return new BlockWriter(tree.source(), tree.anchors(), folder, cancel).write(tree.document());
  }
}
