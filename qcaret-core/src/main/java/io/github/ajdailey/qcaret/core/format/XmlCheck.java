package io.github.ajdailey.qcaret.core.format;

import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.xml.SecureXml;
import java.io.IOException;
import java.io.Reader;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

/**
 * XML 1.0 well-formedness of a range through {@link SecureXml#checkWellFormed}, reading it as a
 * character stream, as one document or as a fragment: an internal DTD subset is read, nothing
 * outside the text is.
 */
final class XmlCheck {
  /** Chars a read hands the parser at most between cancellation checks. */
  static final int READ_CHARS = 8192;

  private XmlCheck() {}

  /** Opens the synthetic root a fragment is parsed inside; it never reaches the text. */
  static final String FRAGMENT_OPEN = "<qcaret-fragment>";

  private static final String FRAGMENT_CLOSE = "</qcaret-fragment>";

  /**
   * Parses the range as one document.
   *
   * @param source the range's text
   * @param token checked on every read
   * @throws FormatException at the parser's first fatal error
   * @throws java.util.concurrent.CancellationException when canceled
   */
  static void check(String source, CancellationToken token) {
    check(source, token, null, false);
  }

  /**
   * Parses the range as a fragment: content inside {@link #FRAGMENT_OPEN}, which adds no line. An
   * XML declaration or a DOCTYPE in the range is an error at its position.
   *
   * @param source the range's text
   * @param token checked on every read
   * @throws FormatException at the first error, in range coordinates
   * @throws java.util.concurrent.CancellationException when canceled
   */
  static void checkFragment(String source, CancellationToken token) {
    check(source, token, null, true);
  }

  /**
   * Parses the range as {@link #check} or {@link #checkFragment} does, running {@code onRead}
   * before each read checks the token.
   *
   * @param source the range's text
   * @param token checked on every read
   * @param onRead runs before each check, or null
   * @param fragment true to parse the range as a fragment
   */
  static void check(String source, CancellationToken token, Runnable onRead, boolean fragment) {
    String text = source;
    int shift = 0;
    if (fragment) {
      refuseProlog(source);
      text = FRAGMENT_OPEN + source + FRAGMENT_CLOSE;
      shift = FRAGMENT_OPEN.length();
    }
    try {
      SecureXml.checkWellFormed(new InputSource(new SourceReader(text, token, onRead)));
    } catch (SAXParseException e) {
      int line = e.getLineNumber();
      int column = line == 1 ? e.getColumnNumber() - shift : e.getColumnNumber();
      throw new FormatException(
          Math.min(offset(source, line, column), source.length()), message(e));
    } catch (SAXException e) {
      throw new FormatException(0, message(e));
    } catch (IOException e) {
      throw new IllegalStateException("reading a string failed", e);
    }
  }

  /**
   * Fails at the first XML declaration or DOCTYPE outside comments, CDATA, PIs and attribute
   * values. An unterminated construct ends the scan; the parser reports it.
   */
  private static void refuseProlog(String s) {
    int i = 0;
    int n = s.length();
    while (i < n) {
      int lt = s.indexOf('<', i);
      if (lt < 0) {
        return;
      }
      int end;
      if (s.startsWith("<!--", lt)) {
        end = s.indexOf("-->", lt + 4);
      } else if (s.startsWith("<![CDATA[", lt)) {
        end = s.indexOf("]]>", lt + 9);
      } else if (s.startsWith("<!DOCTYPE", lt)) {
        throw new FormatException(lt, "An XML fragment cannot hold a DOCTYPE");
      } else if (s.startsWith("<?", lt)) {
        if (s.startsWith("<?xml", lt)
            && (lt + 5 == n || s.charAt(lt + 5) == '?' || isSpace(s.charAt(lt + 5)))) {
          throw new FormatException(lt, "An XML fragment cannot hold an XML declaration");
        }
        end = s.indexOf("?>", lt + 2);
      } else {
        end = tagEnd(s, lt + 1);
      }
      if (end < 0) {
        return;
      }
      i = end + 1;
    }
  }

  /** Returns the offset of the {@code >} ending the tag, skipping quoted values; -1 if none. */
  private static int tagEnd(String s, int from) {
    int i = from;
    while (i < s.length()) {
      char c = s.charAt(i);
      if (c == '"' || c == '\'') {
        int close = s.indexOf(c, i + 1);
        if (close < 0) {
          return -1;
        }
        i = close + 1;
      } else if (c == '>') {
        return i;
      } else {
        i++;
      }
    }
    return -1;
  }

  private static boolean isSpace(char c) {
    return c == ' ' || c == '\t' || c == '\n' || c == '\r';
  }

  private static String message(SAXException e) {
    String message = e.getMessage();
    return message == null ? e.getClass().getSimpleName() : message;
  }

  /**
   * Maps the parser's 1-based line and UTF-16 column to a range offset; LF, CR LF and CR each end a
   * line. An unknown position maps to 0, a column past the line's end to the end.
   */
  static int offset(String source, int line, int column) {
    if (line < 1) {
      return 0;
    }
    int at = 0;
    int n = source.length();
    for (int l = 1; l < line && at < n; l++) {
      while (at < n && source.charAt(at) != '\n' && source.charAt(at) != '\r') {
        at++;
      }
      if (at < n && source.charAt(at) == '\r' && at + 1 < n && source.charAt(at + 1) == '\n') {
        at++;
      }
      if (at < n) {
        at++;
      }
    }
    int end = at;
    while (end < n && source.charAt(end) != '\n' && source.charAt(end) != '\r') {
      end++;
    }
    return Math.min(at + Math.max(column, 1) - 1, end);
  }

  /** The range's text as a reader that checks the token on every read. */
  private static final class SourceReader extends Reader {
    private final String source;
    private final CancellationToken token;
    private final Runnable onRead;
    private int at;

    SourceReader(String source, CancellationToken token, Runnable onRead) {
      this.source = source;
      this.token = token;
      this.onRead = onRead;
    }

    @Override
    public int read(char[] buffer, int offset, int length) {
      if (onRead != null) {
        onRead.run();
      }
      token.check();
      if (at >= source.length()) {
        return -1;
      }
      int count = Math.min(Math.min(length, READ_CHARS), source.length() - at);
      source.getChars(at, at + count, buffer, offset);
      at += count;
      return count;
    }

    @Override
    public void close() {
      // Nothing to release.
    }
  }
}
