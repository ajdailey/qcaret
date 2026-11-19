package io.github.ajdailey.qcaret.core.format;

import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The JSON path at a caret ({@link CaretPath}), from the tokens {@link JsonLayout#scan} reads up to
 * the caret. A caret on a name, a value or a container's bracket takes that member or element; one
 * on a closing bracket or a comma, or in the gap after a comma, takes the container. A caret
 * touching the end of a token counts as on it.
 */
final class JsonPath implements JsonLayout.Tokens {
  /** Ends the scan once the caret is placed. */
  private static final class Found extends RuntimeException {
    private static final long serialVersionUID = 1L;

    Found() {
      super(null, null, false, false);
    }
  }

  /** An open object or array: its current name or index, and whether a member is under way. */
  private static final class Frame {
    final boolean object;
    String name;
    int index = -1;
    boolean active;

    Frame(boolean object) {
      this.object = object;
    }
  }

  private final String s;
  private final int caret;
  private final List<Frame> frames = new ArrayList<Frame>();
  private String path;

  private JsonPath(String source, int caret) {
    this.s = source;
    this.caret = caret;
  }

  /**
   * Returns the path at a caret.
   *
   * @param source the whole text
   * @param caret the caret's offset
   * @param strict true to refuse comments
   * @param token checked as the text is read
   * @return the path
   * @throws FormatException for malformed text before the caret
   */
  static String find(String source, int caret, boolean strict, CancellationToken token) {
    JsonPath tracker = new JsonPath(source, caret);
    try {
      JsonLayout.scan(source, strict, tracker, token);
    } catch (Found e) {
      return tracker.path;
    } catch (FormatException e) {
      if (e.at() < caret) {
        throw e;
      }
    }
    return tracker.path(true);
  }

  @Override
  public void token(int kind, int start, int end) {
    if (caret < start) {
      found(path(true));
    }
    Frame top = frames.isEmpty() ? null : frames.get(frames.size() - 1);
    boolean on = caret <= end;
    switch (kind) {
      case JsonLayout.NAME -> {
        top.name = name(start, end);
        top.active = true;
      }
      case JsonLayout.VALUE, JsonLayout.OPEN -> {
        if (top != null && !top.object) {
          top.index++;
          top.active = true;
        }
        if (kind == JsonLayout.OPEN && !on) {
          frames.add(new Frame(s.charAt(start) == '{'));
        }
      }
      case JsonLayout.CLOSE -> {
        if (on) {
          found(path(false));
        }
        frames.remove(frames.size() - 1);
      }
      case JsonLayout.COMMA -> {
        if (on) {
          found(path(caret == start));
        }
        top.active = false;
      }
      default -> {
        // A colon or a comment changes no path.
      }
    }
    if (on) {
      found(path(true));
    }
  }

  private void found(String value) {
    path = value;
    throw new Found();
  }

  /** Returns the path of the open frames; the innermost one's member only when asked. */
  private String path(boolean member) {
    StringBuilder out = new StringBuilder("$");
    for (int i = 0; i < frames.size(); i++) {
      Frame f = frames.get(i);
      if (i == frames.size() - 1 && !(member && f.active)) {
        break;
      }
      if (!f.object) {
        out.append('[').append(f.index).append(']');
      } else if (identifier(f.name)) {
        out.append('.').append(f.name);
      } else {
        quoted(out, f.name);
      }
    }
    return out.toString();
  }

  /** Writes {@code ['name']}, escaping quotes, backslashes and control characters. */
  private static void quoted(StringBuilder out, String name) {
    out.append("['");
    for (int k = 0; k < name.length(); k++) {
      char c = name.charAt(k);
      switch (c) {
        case '\'', '\\' -> out.append('\\').append(c);
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        default -> {
          if (c < 0x20) {
            out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
          } else {
            out.append(c);
          }
        }
      }
    }
    out.append("']");
  }

  private static boolean identifier(String name) {
    if (name.isEmpty()) {
      return false;
    }
    for (int i = 0; i < name.length(); i++) {
      char c = name.charAt(i);
      boolean letter = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || c == '_' || c == '$';
      if (!letter && (i == 0 || c < '0' || c > '9')) {
        return false;
      }
    }
    return true;
  }

  /** Decodes the name string {@code [start, end)}, which the scan has checked. */
  private String name(int start, int end) {
    StringBuilder out = new StringBuilder(end - start);
    int i = start + 1;
    while (i < end - 1) {
      char c = s.charAt(i);
      if (c != '\\') {
        out.append(c);
        i++;
        continue;
      }
      char e = s.charAt(i + 1);
      switch (e) {
        case 'u' -> {
          out.append((char) Integer.parseInt(s.substring(i + 2, i + 6), 16));
          i += 6;
          continue;
        }
        case 'b' -> out.append('\b');
        case 'f' -> out.append('\f');
        case 'n' -> out.append('\n');
        case 'r' -> out.append('\r');
        case 't' -> out.append('\t');
        default -> out.append(e);
      }
      i += 2;
    }
    return out.toString();
  }
}
