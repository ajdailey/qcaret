package io.github.ajdailey.qcaret.core.markdown;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.commonmark.ext.footnotes.FootnoteReference;
import org.commonmark.ext.gfm.strikethrough.Strikethrough;
import org.commonmark.ext.image.attributes.ImageAttributes;
import org.commonmark.ext.task.list.items.TaskListItemMarker;
import org.commonmark.node.Code;
import org.commonmark.node.Emphasis;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.HtmlInline;
import org.commonmark.node.Image;
import org.commonmark.node.Link;
import org.commonmark.node.Node;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.SourceSpan;
import org.commonmark.node.StrongEmphasis;
import org.commonmark.node.Text;

/**
 * Writes a block's inline content into a {@link MarkdownOutput}. A soft line break is a space; a
 * hard line break and {@code <br>} start a new rendered line, or write a space in a table cell.
 * {@code <kbd>}, {@code <sub>} and {@code <sup>} open a style run that the matching close tag, or
 * the block's end, closes. Any other inline HTML is written as its source text.
 */
final class InlineWriter {
  private static final Pattern TAG =
      Pattern.compile(
          "<\\s*(/?)\\s*(?:(br)|(kbd)|(sub)|(sup))\\s*/?\\s*>", Pattern.CASE_INSENSITIVE);

  private final MarkdownOutput out;
  private final Path folder;

  /** Footnote numbers by label, in the order of the first reference. */
  final Map<String, Integer> footnotes = new LinkedHashMap<String, Integer>();

  private final List<int[]> open = new ArrayList<int[]>();
  private List<SourceSpan> spans = List.of();
  private int spanIndex;
  private boolean cell;

  InlineWriter(MarkdownOutput out, Path folder) {
    this.out = out;
    this.folder = folder;
  }

  /**
   * Writes a block's children on the current line.
   *
   * @param block the block
   * @param lineSpans the block's source spans, one per source line, to map line breaks
   * @param inCell true for a table cell
   */
  void write(Node block, List<SourceSpan> lineSpans, boolean inCell) {
    spans = lineSpans;
    spanIndex = 0;
    cell = inCell;
    children(block);
    for (int i = open.size() - 1; i >= 0; i--) {
      int[] run = open.get(i);
      out.run(run[1], out.length(), InlineStyle.values()[run[0]], 0);
    }
    open.clear();
  }

  private void children(Node parent) {
    for (Node child = parent.getFirstChild(); child != null; child = child.getNext()) {
      node(child);
    }
  }

  private void node(Node node) {
    int start = out.length();
    if (node instanceof Text t) {
      text(t.getLiteral());
    } else if (node instanceof Code c) {
      text(c.getLiteral());
      out.run(start, out.length(), InlineStyle.CODE, 0);
    } else if (node instanceof Emphasis) {
      styled(node, InlineStyle.EMPHASIS);
    } else if (node instanceof StrongEmphasis) {
      styled(node, InlineStyle.STRONG);
    } else if (node instanceof Strikethrough) {
      styled(node, InlineStyle.STRIKETHROUGH);
    } else if (node instanceof Link link) {
      children(link);
      if (out.length() > start) {
        out.run(start, out.length(), InlineStyle.LINK, 0);
        out.links.add(
            new MarkdownLink(start, out.length(), LinkTarget.of(link.getDestination(), folder)));
      }
    } else if (node instanceof Image image) {
      image(image);
    } else if (node instanceof SoftLineBreak) {
      spanIndex++;
      out.append(' ');
    } else if (node instanceof HardLineBreak) {
      spanIndex++;
      lineBreak();
    } else if (node instanceof HtmlInline html) {
      html(html.getLiteral());
    } else if (node instanceof FootnoteReference ref) {
      Integer number = footnotes.get(ref.getLabel());
      if (number == null) {
        number = footnotes.size() + 1;
        footnotes.put(ref.getLabel(), number);
      }
      out.append(Integer.toString(number));
      out.run(start, out.length(), InlineStyle.SUP, 0);
      out.footnoteReferences.add(new MarkdownFootnote.Reference(start, out.length(), number));
    } else if (!(node instanceof TaskListItemMarker) && !(node instanceof ImageAttributes)) {
      children(node);
    }
  }

  private void styled(Node node, InlineStyle style) {
    int start = out.length();
    children(node);
    out.run(start, out.length(), style, 0);
  }

  private void text(String literal) {
    out.append(cell ? literal.replace('\t', ' ') : literal);
  }

  private void lineBreak() {
    if (cell) {
      out.append(' ');
      return;
    }
    int index = Math.min(spanIndex, spans.size() - 1);
    out.startLine(index < 0 ? 0 : spans.get(index).getLineIndex(), 0);
  }

  private void image(Image image) {
    int start = out.length();
    String alt = HeadingAnchors.plainText(image);
    LinkTarget target = LinkTarget.of(image.getDestination(), folder);
    String width = null;
    String height = null;
    for (Node child = image.getFirstChild(); child != null; child = child.getNext()) {
      if (child instanceof ImageAttributes attributes) {
        width = attributes.getAttributes().get("width");
        height = attributes.getAttributes().get("height");
      }
    }
    if (MarkdownImage.drawable(target)) {
      out.append(MarkdownImage.PLACEHOLDER);
    } else {
      text(alt);
      if (!image.getDestination().isEmpty()) {
        text((alt.isEmpty() ? "(" : " (") + image.getDestination() + ")");
      }
    }
    out.images.add(new MarkdownImage(start, out.length(), alt, target, width, height));
  }

  private void html(String literal) {
    Matcher m = TAG.matcher(literal);
    if (!m.matches()) {
      text(literal);
      return;
    }
    if (m.group(2) != null) {
      if (m.group(1).isEmpty()) {
        lineBreak();
      }
      return;
    }
    InlineStyle style;
    if (m.group(3) != null) {
      style = InlineStyle.KBD;
    } else if (m.group(4) != null) {
      style = InlineStyle.SUB;
    } else {
      style = InlineStyle.SUP;
    }
    if (m.group(1).isEmpty()) {
      open.add(new int[] {style.ordinal(), out.length()});
      return;
    }
    for (int i = open.size() - 1; i >= 0; i--) {
      if (open.get(i)[0] == style.ordinal()) {
        out.run(open.get(i)[1], out.length(), style, 0);
        open.remove(i);
        return;
      }
    }
  }
}
