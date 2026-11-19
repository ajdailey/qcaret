package io.github.ajdailey.qcaret.core.format;

import io.github.ajdailey.qcaret.core.buffer.TextContent;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import java.util.concurrent.CancellationException;

/**
 * Edit > Copy XML Path and Copy JSON Path: the path of the element, attribute, member or element at
 * a caret in the whole text, read with the tokenizers Pretty Print uses.
 *
 * <ul>
 *   <li>XML ({@link XmlPath}): {@code /a/b[2]/c}, with an index from 1 only where siblings share
 *       the name, names and prefixes as written, and {@code /@name} for an attribute. A caret in
 *       text, CDATA, a comment or a PI takes the enclosing element; one outside every element takes
 *       {@code /}. A text of several top-level items is read as a fragment.
 *   <li>JSON ({@link JsonPath}): {@code $.a[1].b}, with an index from 0, and {@code ['key']} for a
 *       name that is not an identifier ({@code [A-Za-z_$][A-Za-z0-9_$]*}), with quotes, backslashes
 *       and control characters escaped. Comments follow {@code editor.strictJson}.
 * </ul>
 *
 * Malformed text before the caret is an error; malformed text at or after it is not.
 */
public final class CaretPath {
  private CaretPath() {}

  /**
   * The outcome of {@link #find}.
   *
   * @param path the path; null with an error
   * @param error the first error before the caret; null with a path
   */
  public record Result(String path, FormatError error) {}

  /**
   * Finds the path at a caret, on a worker.
   *
   * @param language the format
   * @param text the whole text
   * @param caret the caret's offset
   * @param strictJson true to refuse JSON comments
   * @param token checked as the text is read
   * @return the path or the error
   * @throws CancellationException when canceled
   */
  public static Result find(
      Formats.Language language,
      TextContent text,
      int caret,
      boolean strictJson,
      CancellationToken token) {
    String source = text.subSequence(0, text.length()).toString();
    try {
      String path =
          language == Formats.Language.XML
              ? XmlPath.find(source, caret, token)
              : JsonPath.find(source, caret, strictJson, token);
      return new Result(path, null);
    } catch (FormatException e) {
      int line = text.lineAt(e.at());
      return new Result(
          null,
          new FormatError(e.at(), line + 1, e.at() - text.offsetOfLine(line) + 1, e.getMessage()));
    }
  }
}
