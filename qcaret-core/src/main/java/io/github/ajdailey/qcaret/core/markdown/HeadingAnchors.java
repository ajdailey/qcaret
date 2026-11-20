package io.github.ajdailey.qcaret.core.markdown;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.commonmark.node.Code;
import org.commonmark.node.Heading;
import org.commonmark.node.HtmlInline;
import org.commonmark.node.Node;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.Text;

/**
 * Every heading's anchor id, assigned in document order before rendering so a table of contents can
 * link to headings below it. Ids follow GitLab's rules: the heading's text without HTML, lower
 * case; letters, digits, {@code _} and {@code -} kept; each space becomes {@code -}; other chars
 * dropped; a run of {@code -} becomes one; a repeated id gets {@code -1}, {@code -2} in order.
 *
 * <p>commonmark's {@code IdGenerator} does not join {@code -} runs, so {@code a b} would get {@code
 * a--b} where GitLab links to {@code a-b}; this class replaces it.
 */
final class HeadingAnchors {
  /** A heading in document order. */
  record Entry(Heading heading, String text, String anchor) {}

  private final Map<Node, Entry> byNode = new IdentityHashMap<Node, Entry>();
  private final List<Entry> entries = new ArrayList<Entry>();

  /** Collects the headings under {@code root}. */
  HeadingAnchors(Node root) {
    Map<String, Integer> seen = new HashMap<String, Integer>();
    collect(root, seen);
  }

  private void collect(Node node, Map<String, Integer> seen) {
    for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
      if (child instanceof Heading h) {
        String text = plainText(h);
        String id = id(text);
        Integer count = seen.get(id);
        seen.put(id, count == null ? 1 : count + 1);
        Entry entry = new Entry(h, text, count == null ? id : id + "-" + count);
        byNode.put(h, entry);
        entries.add(entry);
      } else {
        collect(child, seen);
      }
    }
  }

  /** Returns a heading's text and anchor. */
  Entry entry(Heading heading) {
    Entry entry = byNode.get(heading);
    if (entry == null) {
      String text = plainText(heading);
      entry = new Entry(heading, text, id(text));
    }
    return entry;
  }

  /** Returns the headings in document order. */
  List<Entry> entries() {
    return entries;
  }

  /** The id of a heading's text before repeats are numbered. */
  static String id(String text) {
    String lower = text.toLowerCase(Locale.ROOT);
    StringBuilder out = new StringBuilder(lower.length());
    int i = 0;
    while (i < lower.length()) {
      int c = lower.codePointAt(i);
      if (Character.isLetterOrDigit(c) || c == '_') {
        out.appendCodePoint(c);
      } else if ((c == '-' || c == ' ')
          && (out.length() == 0 || out.charAt(out.length() - 1) != '-')) {
        out.append('-');
      }
      i += Character.charCount(c);
    }
    return out.toString();
  }

  /** The text of inline content: text and code literals, without HTML. */
  static String plainText(Node node) {
    StringBuilder out = new StringBuilder();
    append(node, out);
    return out.toString();
  }

  private static void append(Node node, StringBuilder out) {
    for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
      if (child instanceof Text t) {
        out.append(t.getLiteral());
      } else if (child instanceof Code c) {
        out.append(c.getLiteral());
      } else if (child instanceof SoftLineBreak) {
        out.append(' ');
      } else if (!(child instanceof HtmlInline)) {
        append(child, out);
      }
    }
  }
}
