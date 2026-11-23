package io.github.ajdailey.qcaret.core.markdown;

import java.util.ArrayList;
import java.util.List;
import org.commonmark.node.Heading;
import org.commonmark.node.Node;
import org.commonmark.node.SourceSpan;

/**
 * A document {@link MarkdownRenderer#parse} parsed: commonmark's tree with block source spans, the
 * source, and each heading's GitLab anchor. Other renderers walk the tree; it is not modified after
 * the parse. Read on one thread at a time.
 */
public final class MarkdownTree {
  /** The node class of GitLab's {@code >>>} quote, a container of blocks. */
  public static final Class<? extends Node> MULTILINE_QUOTE = GitLabBlocks.MultilineQuote.class;

  /** The node class of a {@code [[_TOC_]]} or {@code [TOC]} marker, a leaf. */
  public static final Class<? extends Node> TOC = GitLabBlocks.Toc.class;

  /**
   * A heading in document order.
   *
   * @param level 1 to 6
   * @param text the text without HTML
   * @param anchor the GitLab id
   */
  public record Entry(int level, String text, String anchor) {}

  private final String source;
  private final Node document;
  private final HeadingAnchors anchors;

  MarkdownTree(String source, Node document) {
    this.source = source;
    this.document = document;
    this.anchors = new HeadingAnchors(document);
  }

  /** Returns the source the tree was parsed from. */
  public String source() {
    return source;
  }

  /** Returns the document node. */
  public Node document() {
    return document;
  }

  HeadingAnchors anchors() {
    return anchors;
  }

  /**
   * Returns a heading's anchor.
   *
   * @param heading a heading of this tree
   * @return the GitLab id, numbered when an earlier heading has the same id
   */
  public String anchor(Heading heading) {
    return anchors.entry(heading).anchor();
  }

  /** Returns the headings in document order. */
  public List<Entry> headings() {
    List<Entry> out = new ArrayList<Entry>();
    for (HeadingAnchors.Entry e : anchors.entries()) {
      out.add(new Entry(e.heading().getLevel(), e.text(), e.anchor()));
    }
    return out;
  }

  /**
   * Finds the heading a link to this document names, as {@link RenderedMarkdown#headingLine} does,
   * without rendering.
   *
   * @param anchor the GitLab id, compared exactly
   * @return the heading's first 0-based source line, or -1 when no heading has the id
   */
  public int headingSourceLine(String anchor) {
    for (HeadingAnchors.Entry e : anchors.entries()) {
      if (e.anchor().equals(anchor)) {
        return firstLine(e.heading());
      }
    }
    return -1;
  }

  /**
   * Returns a block's first source line.
   *
   * @param block a block of this tree
   * @return the 0-based line, or -1 for a node without source spans
   */
  public static int firstLine(Node block) {
    List<SourceSpan> spans = block.getSourceSpans();
    return spans.isEmpty() ? -1 : spans.get(0).getLineIndex();
  }

  /**
   * Returns a front matter block's lines without the {@code ---} fences.
   *
   * @param block a {@code YamlFrontMatterBlock} of this tree
   * @return the lines, joined by LF
   */
  public String frontMatter(Node block) {
    List<SourceSpan> spans = block.getSourceSpans();
    StringBuilder out = new StringBuilder();
    for (int i = 1; i < spans.size(); i++) {
      SourceSpan span = spans.get(i);
      String text = source.substring(span.getInputIndex(), span.getInputIndex() + span.getLength());
      String fence = text.strip();
      if (i == spans.size() - 1 && (fence.equals("---") || fence.equals("..."))) {
        break;
      }
      if (out.length() > 0) {
        out.append('\n');
      }
      out.append(text);
    }
    return out.toString();
  }

  /**
   * Returns the language a fenced code block's info string names, as the render colors it.
   *
   * @param info the info string, or null
   * @return its first word without braces or a leading dot, or null
   */
  public static String language(String info) {
    return BlockWriter.language(info);
  }
}
