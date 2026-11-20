package io.github.ajdailey.qcaret.core.markdown;

import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.WorkerThread;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.commonmark.ext.footnotes.FootnoteDefinition;
import org.commonmark.ext.front.matter.YamlFrontMatterBlock;
import org.commonmark.ext.gfm.alerts.Alert;
import org.commonmark.ext.gfm.alerts.AlertTitle;
import org.commonmark.ext.gfm.tables.TableBlock;
import org.commonmark.ext.gfm.tables.TableCell;
import org.commonmark.ext.gfm.tables.TableRow;
import org.commonmark.ext.task.list.items.TaskListItemMarker;
import org.commonmark.node.BlockQuote;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.Heading;
import org.commonmark.node.HtmlBlock;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.LinkReferenceDefinition;
import org.commonmark.node.ListBlock;
import org.commonmark.node.Node;
import org.commonmark.node.OrderedList;
import org.commonmark.node.Paragraph;
import org.commonmark.node.SourceSpan;
import org.commonmark.node.ThematicBreak;

/**
 * Walks a parsed document's blocks into a {@link MarkdownOutput}, checking the cancellation token
 * before each block. A block that writes no line, such as a link reference definition, gets no
 * separator line.
 */
final class BlockWriter {
  private static final Pattern DETAILS =
      Pattern.compile("</?details(\\s[^>]*)?>", Pattern.CASE_INSENSITIVE);
  private static final Pattern SUMMARY =
      Pattern.compile("<summary>(.*)</summary>", Pattern.CASE_INSENSITIVE);

  private final MarkdownOutput out = new MarkdownOutput();
  private final String source;
  private final CancellationToken cancel;
  private final InlineWriter inline;
  private final HeadingAnchors anchors;
  private final Map<String, FootnoteDefinition> definitions =
      new HashMap<String, FootnoteDefinition>();

  /** The source line of a separator line the next block line writes first, or -1. */
  private int separator = -1;

  BlockWriter(String source, HeadingAnchors anchors, Path folder, CancellationToken cancel) {
    this.source = source;
    this.cancel = cancel;
    this.inline = new InlineWriter(out, folder);
    this.anchors = anchors;
  }

  @WorkerThread
  RenderedMarkdown write(Node document) {
    children(document, true);
    footnotes();
    return out.build();
  }

  /** Writes a container's blocks, with an empty line between two when {@code separated}. */
  private void children(Node parent, boolean separated) {
    Node previous = null;
    for (Node child = parent.getFirstChild(); child != null; child = child.getNext()) {
      if (child instanceof LinkReferenceDefinition
          || child instanceof TaskListItemMarker
          || child instanceof AlertTitle) {
        continue;
      }
      if (child instanceof FootnoteDefinition d) {
        definitions.putIfAbsent(d.getLabel(), d);
        continue;
      }
      int before = out.lineCount();
      if (previous != null && separated) {
        separator = Math.min(lastLine(previous) + 1, firstLine(child));
      }
      block(child);
      separator = -1;
      if (out.lineCount() > before) {
        previous = child;
      }
    }
  }

  /** Starts a block line, after a pending separator. */
  private void line(int sourceLine, int flags) {
    flushSeparator();
    out.startLine(sourceLine, flags);
  }

  /**
   * Writes a pending separator now, at the current depths; a container calls it before raising
   * them, and always writes a line.
   */
  private void flushSeparator() {
    if (separator >= 0) {
      int at = separator;
      separator = -1;
      out.startLine(at, 0);
    }
  }

  private void block(Node node) {
    cancel.check();
    int first = nextLine();
    MarkdownBlock.Kind kind = null;
    if (node instanceof Paragraph p) {
      line(firstLine(p), 0);
      inline.write(p, p.getSourceSpans(), false);
      kind = MarkdownBlock.Kind.PARAGRAPH;
    } else if (node instanceof Heading h) {
      line(firstLine(h), 0);
      int start = out.length();
      inline.write(h, h.getSourceSpans(), false);
      out.run(start, out.length(), InlineStyle.HEADING, h.getLevel());
      HeadingAnchors.Entry entry = anchors.entry(h);
      out.heading(h.getLevel(), entry.text(), entry.anchor(), firstLine(h));
      kind = MarkdownBlock.Kind.HEADING;
    } else if (node instanceof ThematicBreak) {
      line(firstLine(node), RenderedMarkdown.RULE);
      kind = MarkdownBlock.Kind.RULE;
    } else if (node instanceof FencedCodeBlock f) {
      List<String> lines = lines(f.getLiteral());
      code(lines, sourceLines(f, 1, lines.size()), language(f.getInfo()));
      kind = MarkdownBlock.Kind.CODE;
    } else if (node instanceof IndentedCodeBlock c) {
      List<String> lines = lines(c.getLiteral());
      code(lines, sourceLines(c, 0, lines.size()), null);
      kind = MarkdownBlock.Kind.CODE;
    } else if (node instanceof YamlFrontMatterBlock) {
      frontMatter(node);
      kind = MarkdownBlock.Kind.CODE;
    } else if (node instanceof HtmlBlock html) {
      html(html);
      kind = MarkdownBlock.Kind.HTML;
    } else if (node instanceof TableBlock table) {
      table(table);
      kind = MarkdownBlock.Kind.TABLE;
    } else if (node instanceof GitLabBlocks.Toc) {
      toc(firstLine(node));
      kind = MarkdownBlock.Kind.TOC;
    } else if (node instanceof Alert alert) {
      flushSeparator();
      alert(alert);
    } else if (node instanceof BlockQuote || node instanceof GitLabBlocks.MultilineQuote) {
      flushSeparator();
      out.quoteDepth++;
      int before = out.lineCount();
      children(node, true);
      if (out.lineCount() == before) {
        empty(firstLine(node));
      }
      out.quoteDepth--;
    } else if (node instanceof ListBlock list) {
      flushSeparator();
      list(list);
    } else {
      children(node, true);
    }
    if (kind != null) {
      leaf(kind, first);
    }
  }

  /** The line the next {@link #line} call starts, after a pending separator. */
  private int nextLine() {
    return out.lineCount() + (separator >= 0 ? 1 : 0);
  }

  /** Records a leaf block from {@code first} to the last line written, when it wrote any. */
  private void leaf(MarkdownBlock.Kind kind, int first) {
    if (out.lineCount() > first) {
      out.blocks.add(new MarkdownBlock(kind, first, out.lineCount() - first));
    }
  }

  /** Writes the empty line of an empty item or quote. */
  private void empty(int sourceLine) {
    int first = nextLine();
    line(sourceLine, 0);
    leaf(MarkdownBlock.Kind.EMPTY, first);
  }

  private void list(ListBlock list) {
    out.listDepth++;
    RenderedMarkdown.ListMarker marker;
    int number = 0;
    if (list instanceof OrderedList ordered) {
      marker = RenderedMarkdown.ListMarker.NUMBER;
      Integer start = ordered.getMarkerStartNumber();
      number = start == null ? 1 : start;
    } else {
      marker = RenderedMarkdown.ListMarker.BULLET;
    }
    Node previous = null;
    for (Node item = list.getFirstChild(); item != null; item = item.getNext()) {
      cancel.check();
      if (previous != null && !list.isTight()) {
        line(Math.min(lastLine(previous) + 1, firstLine(item)), 0);
      }
      out.setMarker(marker, number, task(item));
      children(item, !list.isTight());
      if (out.markerPending()) {
        empty(firstLine(item));
      }
      previous = item;
      number++;
    }
    out.listDepth--;
  }

  private static RenderedMarkdown.TaskState task(Node item) {
    Node first = item.getFirstChild();
    if (first instanceof Paragraph) {
      first = first.getFirstChild();
    }
    if (first instanceof TaskListItemMarker marker) {
      return marker.isChecked() ? RenderedMarkdown.TaskState.DONE : RenderedMarkdown.TaskState.OPEN;
    }
    return RenderedMarkdown.TaskState.NONE;
  }

  private void table(TableBlock table) {
    List<MarkdownTable.Alignment> alignments = new ArrayList<MarkdownTable.Alignment>();
    List<int[]> rows = new ArrayList<int[]>();
    int firstLine = -1;
    int columns = 0;
    int rowIndex = 0;
    for (Node section = table.getFirstChild(); section != null; section = section.getNext()) {
      for (Node row = section.getFirstChild(); row != null; row = row.getNext()) {
        if (!(row instanceof TableRow)) {
          continue;
        }
        cancel.check();
        if (rows.isEmpty()) {
          for (Node c = row.getFirstChild(); c != null; c = c.getNext()) {
            alignments.add(alignment(((TableCell) c).getAlignment()));
          }
          columns = alignments.size();
        }
        int fallback = firstLine(table) + rowIndex + (rowIndex > 0 ? 1 : 0);
        line(row.getSourceSpans().isEmpty() ? fallback : firstLine(row), 0);
        if (firstLine < 0) {
          firstLine = out.lineCount() - 1;
        }
        int[] cells = new int[columns * 2];
        Node cell = row.getFirstChild();
        for (int col = 0; col < columns; col++) {
          if (col > 0) {
            out.append('\t');
          }
          cells[col * 2] = out.length();
          if (cell != null) {
            inline.write(cell, row.getSourceSpans(), true);
            cell = cell.getNext();
          }
          cells[col * 2 + 1] = out.length();
        }
        rows.add(cells);
        rowIndex++;
      }
    }
    if (!rows.isEmpty()) {
      out.tables.add(new MarkdownTable(firstLine, alignments, rows.toArray(new int[0][])));
    }
  }

  private static MarkdownTable.Alignment alignment(TableCell.Alignment alignment) {
    if (alignment == null) {
      return MarkdownTable.Alignment.NONE;
    }
    return switch (alignment) {
      case LEFT -> MarkdownTable.Alignment.LEFT;
      case CENTER -> MarkdownTable.Alignment.CENTER;
      case RIGHT -> MarkdownTable.Alignment.RIGHT;
    };
  }

  private void alert(Alert alert) {
    out.quoteDepth++;
    line(firstLine(alert), 0);
    int first = out.lineCount() - 1;
    int start = out.length();
    String kind = alert.getType().toLowerCase(Locale.ROOT);
    Node title = alert.getFirstChild();
    if (title instanceof AlertTitle) {
      inline.write(title, title.getSourceSpans(), false);
    } else {
      out.append(Character.toUpperCase(kind.charAt(0)) + kind.substring(1));
    }
    out.run(start, out.length(), InlineStyle.STRONG, 0);
    leaf(MarkdownBlock.Kind.ALERT_TITLE, first);
    children(alert, true);
    out.quoteDepth--;
    out.alerts.add(new MarkdownAlert(first, out.lineCount() - first, kind));
  }

  /** Writes the headings as a nested bullet list of links to their anchors. */
  private void toc(int sourceLine) {
    int top = 6;
    for (HeadingAnchors.Entry e : anchors.entries()) {
      top = Math.min(top, e.heading().getLevel());
    }
    flushSeparator();
    int depth = out.listDepth;
    for (HeadingAnchors.Entry e : anchors.entries()) {
      out.listDepth = depth + e.heading().getLevel() - top + 1;
      out.setMarker(RenderedMarkdown.ListMarker.BULLET, 0, RenderedMarkdown.TaskState.NONE);
      line(sourceLine, 0);
      int start = out.length();
      out.append(e.text());
      out.run(start, out.length(), InlineStyle.LINK, 0);
      out.links.add(new MarkdownLink(start, out.length(), LinkTarget.of("#" + e.anchor(), null)));
    }
    out.listDepth = depth;
  }

  private void html(HtmlBlock html) {
    List<String> lines = lines(html.getLiteral());
    int[] at = sourceLines(html, 0, lines.size());
    for (int i = 0; i < lines.size(); i++) {
      String text = lines.get(i);
      String trimmed = text.strip();
      if (DETAILS.matcher(trimmed).matches()) {
        continue;
      }
      line(at[i], 0);
      Matcher summary = SUMMARY.matcher(trimmed);
      if (summary.matches()) {
        int start = out.length();
        out.append(summary.group(1));
        out.run(start, out.length(), InlineStyle.STRONG, 0);
      } else {
        out.append(text);
      }
    }
  }

  private void frontMatter(Node block) {
    List<SourceSpan> spans = block.getSourceSpans();
    List<String> lines = new ArrayList<String>();
    Ints at = new Ints();
    for (int i = 1; i < spans.size(); i++) {
      SourceSpan span = spans.get(i);
      String text = source.substring(span.getInputIndex(), span.getInputIndex() + span.getLength());
      String fence = text.strip();
      if (i == spans.size() - 1 && (fence.equals("---") || fence.equals("..."))) {
        break;
      }
      lines.add(text);
      at.add(span.getLineIndex());
    }
    if (lines.isEmpty()) {
      lines.add("");
      at.add(firstLine(block));
    }
    out.frontMatter = code(lines, at.toArray(), "yaml");
  }

  private CodeBlock code(List<String> lines, int[] sourceLines, String language) {
    int first = -1;
    for (int i = 0; i < lines.size(); i++) {
      line(sourceLines[i], RenderedMarkdown.CODE);
      if (first < 0) {
        first = out.lineCount() - 1;
      }
      out.append(lines.get(i));
    }
    CodeBlock block = new CodeBlock(first, lines.size(), language);
    out.codeBlocks.add(block);
    return block;
  }

  /** An info string's first word without braces or a leading dot, or null. */
  static String language(String info) {
    if (info == null) {
      return null;
    }
    String label = info.strip();
    int space = 0;
    while (space < label.length() && !Character.isWhitespace(label.charAt(space))) {
      space++;
    }
    label = label.substring(0, space);
    int from = 0;
    while (from < label.length() && (label.charAt(from) == '{' || label.charAt(from) == '.')) {
      from++;
    }
    int to = label.length();
    while (to > from && label.charAt(to - 1) == '}') {
      to--;
    }
    label = label.substring(from, to);
    return label.isEmpty() ? null : label;
  }

  /** Writes the referenced footnote definitions after a rule, by number. */
  private void footnotes() {
    List<String> labels = new ArrayList<String>(inline.footnotes.keySet());
    boolean ruled = false;
    for (int i = 0; i < labels.size(); i++) {
      FootnoteDefinition definition = definitions.get(labels.get(i));
      if (definition != null) {
        cancel.check();
        if (!ruled) {
          if (out.lineCount() > 0) {
            separator = firstLine(definition);
          }
          int rule = nextLine();
          line(firstLine(definition), RenderedMarkdown.RULE);
          leaf(MarkdownBlock.Kind.RULE, rule);
          ruled = true;
        }
        out.listDepth = 1;
        out.setMarker(RenderedMarkdown.ListMarker.NUMBER, i + 1, RenderedMarkdown.TaskState.NONE);
        int line = out.lineCount();
        children(definition, true);
        if (out.markerPending()) {
          empty(firstLine(definition));
        }
        out.listDepth = 0;
        out.footnotes.add(new MarkdownFootnote(labels.get(i), i + 1, line));
      }
      if (i == labels.size() - 1) {
        labels = new ArrayList<String>(inline.footnotes.keySet());
      }
    }
  }

  /** A block's lines from its literal, without the final line break; at least one. */
  private static List<String> lines(String literal) {
    List<String> lines = new ArrayList<String>();
    int start = 0;
    while (start < literal.length()) {
      int end = literal.indexOf('\n', start);
      if (end < 0) {
        end = literal.length();
      }
      lines.add(literal.substring(start, end));
      start = end + 1;
    }
    if (lines.isEmpty()) {
      lines.add("");
    }
    return lines;
  }

  /** Each literal line's source line, from the block's spans after {@code skip} fence lines. */
  private static int[] sourceLines(Node block, int skip, int count) {
    List<SourceSpan> spans = block.getSourceSpans();
    int first = firstLine(block) + skip;
    int[] at = new int[count];
    for (int i = 0; i < count; i++) {
      at[i] = i + skip < spans.size() ? spans.get(i + skip).getLineIndex() : first + i;
    }
    return at;
  }

  private static int firstLine(Node node) {
    List<SourceSpan> spans = node.getSourceSpans();
    return spans.isEmpty() ? 0 : spans.get(0).getLineIndex();
  }

  private static int lastLine(Node node) {
    List<SourceSpan> spans = node.getSourceSpans();
    return spans.isEmpty() ? 0 : spans.get(spans.size() - 1).getLineIndex();
  }
}
