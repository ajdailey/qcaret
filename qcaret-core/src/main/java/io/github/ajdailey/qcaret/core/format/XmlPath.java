package io.github.ajdailey.qcaret.core.format;

import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The XML path at a caret ({@link CaretPath}). {@link XmlCheck} reads the whole text, as a fragment
 * when {@link XmlLayout#singleRoot} says it is not one document; its error stands only before the
 * caret. The path comes from {@link XmlLayout#items}, which also counts the same-named siblings
 * after the caret, up to the first construct that does not end. A caret on a tag's {@code <} is in
 * the tag; one after its {@code >} is not.
 */
final class XmlPath {
  /** An open element, or the top level: its name and how many children have each name. */
  private static final class Frame {
    final String name;
    final int ordinal;
    final Frame parent;
    final Map<String, Integer> children = new HashMap<String, Integer>();

    Frame(String name, int ordinal, Frame parent) {
      this.name = name;
      this.ordinal = ordinal;
      this.parent = parent;
    }

    int add(String child) {
      Integer count = children.get(child);
      int next = count == null ? 1 : count + 1;
      children.put(child, next);
      return next;
    }
  }

  private XmlPath() {}

  /**
   * Returns the path at a caret.
   *
   * @param source the whole text
   * @param caret the caret's offset
   * @param token checked as the text is read
   * @return the path
   * @throws FormatException for malformed text before the caret
   */
  static String find(String source, int caret, CancellationToken token) {
    FormatException error = null;
    try {
      if (XmlLayout.singleRoot(source)) {
        XmlCheck.check(source, token);
      } else {
        XmlCheck.checkFragment(source, token);
      }
    } catch (FormatException e) {
      if (e.at() < caret) {
        throw e;
      }
      error = e;
    }
    XmlLayout.Items items = XmlLayout.items(source);
    Frame top = new Frame(null, 0, null);
    Frame open = top;
    Frame target = null;
    String attribute = null;
    for (int k = 0; k < items.count(); k++) {
      if ((k & 1023) == 0) {
        token.check();
      }
      int start = items.starts()[k];
      int end = items.ends()[k];
      byte type = items.types()[k];
      boolean here = target == null && caret >= start && caret < end;
      if (type == XmlLayout.START || type == XmlLayout.EMPTY) {
        String name = tagName(source, start + 1, end);
        Frame element = new Frame(name, open.add(name), open);
        if (here) {
          target = element;
          attribute = attribute(source, start + 1 + name.length(), end, caret);
        }
        if (type == XmlLayout.START) {
          open = element;
        }
      } else if (type == XmlLayout.END) {
        if (here) {
          target = open;
        }
        if (open != top) {
          open = open.parent;
        }
      } else if (here) {
        target = open;
      }
    }
    if (target == null) {
      if (error != null && !items.complete()) {
        throw error;
      }
      target = open;
    }
    return path(target, attribute);
  }

  private static String path(Frame target, String attribute) {
    List<String> steps = new ArrayList<String>();
    for (Frame f = target; f.parent != null; f = f.parent) {
      int same = f.parent.children.get(f.name);
      steps.add(same > 1 ? f.name + "[" + f.ordinal + "]" : f.name);
    }
    StringBuilder out = new StringBuilder();
    for (int i = steps.size() - 1; i >= 0; i--) {
      out.append('/').append(steps.get(i));
    }
    if (attribute != null) {
      out.append("/@").append(attribute);
    }
    return out.length() == 0 ? "/" : out.toString();
  }

  /** Returns the tag name starting at {@code at}. */
  private static String tagName(String s, int at, int end) {
    int i = at;
    while (i < end && !space(s.charAt(i)) && s.charAt(i) != '>' && s.charAt(i) != '/') {
      i++;
    }
    return s.substring(at, i);
  }

  /**
   * Returns the name of the attribute whose name or value holds the caret, in a start tag's
   * attributes from {@code at} to {@code end}; null when the caret is elsewhere.
   */
  private static String attribute(String s, int at, int end, int caret) {
    int i = at;
    while (i < end) {
      while (i < end && space(s.charAt(i))) {
        i++;
      }
      int nameStart = i;
      while (i < end && !space(s.charAt(i)) && "=>/".indexOf(s.charAt(i)) < 0) {
        i++;
      }
      if (i == nameStart) {
        return null;
      }
      int nameEnd = i;
      while (i < end && s.charAt(i) != '"' && s.charAt(i) != '\'') {
        i++;
      }
      int close = i < end ? s.indexOf(s.charAt(i), i + 1) : -1;
      if (close < 0) {
        return null;
      }
      if (caret >= nameStart && caret <= close) {
        return s.substring(nameStart, nameEnd);
      }
      i = close + 1;
    }
    return null;
  }

  private static boolean space(char c) {
    return c == ' ' || c == '\t' || c == '\n' || c == '\r';
  }
}
