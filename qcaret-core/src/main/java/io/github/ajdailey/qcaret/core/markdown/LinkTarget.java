package io.github.ajdailey.qcaret.core.markdown;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * A link or image destination, classified. Classifying reads no file.
 *
 * <ul>
 *   <li>{@code #name} is {@link Kind#ANCHOR}, with the name percent-decoded; an empty destination
 *       is an anchor with an empty name.
 *   <li>A destination with a scheme of two or more chars, or starting {@code //}, is {@link
 *       Kind#URL}, except {@code file:}, which is {@link Kind#FILE} with the URI's path.
 *   <li>Anything else is a {@link Kind#FILE} path: the query and fragment dropped, percent-decoded,
 *       and resolved against the document's folder unless absolute. A one-letter scheme is a
 *       Windows drive.
 *   <li>A {@link Kind#FILE} target's fragment, the text after its first {@code #}, is its {@link
 *       #anchor}, percent-decoded once: the heading the file opens at.
 * </ul>
 *
 * @param kind the classification
 * @param destination the destination as written, after Markdown escapes
 * @param path for {@link Kind#FILE}, the normalized absolute path; null for any other kind, for a
 *     relative path in an untitled document and for a path the file system rejects
 * @param anchor for {@link Kind#ANCHOR}, the name after {@code #}; for {@link Kind#FILE}, the
 *     fragment, null when none or empty; null for {@link Kind#URL}
 */
public record LinkTarget(Kind kind, String destination, Path path, String anchor) {
  /** What a destination points at. */
  public enum Kind {
    /** A local file. */
    FILE,
    /** A heading of the same document. */
    ANCHOR,
    /** Anything with a scheme other than {@code file:}. */
    URL
  }

  private static final Pattern SCHEME =
      Pattern.compile("[A-Za-z][A-Za-z0-9+.\\-]+:.*", Pattern.DOTALL);

  /**
   * Classifies a destination.
   *
   * @param destination the destination, after Markdown escapes
   * @param folder the document's folder, or null for an untitled document
   * @return the target
   */
  public static LinkTarget of(String destination, Path folder) {
    if (destination.isEmpty() || destination.charAt(0) == '#') {
      String name = destination.isEmpty() ? "" : decode(destination.substring(1));
      return new LinkTarget(Kind.ANCHOR, destination, null, name);
    }
    if (destination.startsWith("//")) {
      return new LinkTarget(Kind.URL, destination, null, null);
    }
    if (SCHEME.matcher(destination).matches()) {
      if (!destination.regionMatches(true, 0, "file:", 0, 5)) {
        return new LinkTarget(Kind.URL, destination, null, null);
      }
      int hash = destination.indexOf('#');
      Path path;
      try {
        path =
            Path.of(URI.create(hash < 0 ? destination : destination.substring(0, hash)))
                .normalize();
      } catch (IllegalArgumentException | FileSystemNotFoundException e) {
        path = null;
      }
      return new LinkTarget(Kind.FILE, destination, path, fragment(destination));
    }
    return new LinkTarget(
        Kind.FILE, destination, resolve(destination, folder), fragment(destination));
  }

  /** The decoded text after the first {@code #}; null when there is none or it is empty. */
  private static String fragment(String destination) {
    int hash = destination.indexOf('#');
    if (hash < 0 || hash == destination.length() - 1) {
      return null;
    }
    return decode(destination.substring(hash + 1));
  }

  private static Path resolve(String destination, Path folder) {
    int end = destination.length();
    int hash = destination.indexOf('#');
    if (hash >= 0) {
      end = hash;
    }
    int query = destination.indexOf('?');
    if (query >= 0 && query < end) {
      end = query;
    }
    String text = decode(destination.substring(0, end));
    if (text.isEmpty()) {
      return null;
    }
    Path path;
    try {
      path = Path.of(text);
    } catch (InvalidPathException e) {
      return null;
    }
    if (path.isAbsolute()) {
      return path.normalize();
    }
    return folder == null ? null : folder.resolve(path).normalize();
  }

  /**
   * Decodes {@code %XX} escapes as UTF-8; text with a malformed escape or invalid UTF-8 stays as
   * written. A {@code +} stays a plus sign.
   */
  static String decode(String text) {
    if (text.indexOf('%') < 0) {
      return text;
    }
    ByteBuffer bytes = ByteBuffer.allocate(text.length() * 4);
    int i = 0;
    while (i < text.length()) {
      int c = text.codePointAt(i);
      if (c == '%') {
        if (i + 2 >= text.length()) {
          return text;
        }
        int high = Character.digit(text.charAt(i + 1), 16);
        int low = Character.digit(text.charAt(i + 2), 16);
        if (high < 0 || low < 0) {
          return text;
        }
        bytes.put((byte) (high * 16 + low));
        i += 3;
      } else {
        bytes.put(Character.toString(c).getBytes(StandardCharsets.UTF_8));
        i += Character.charCount(c);
      }
    }
    bytes.flip();
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(bytes)
          .toString();
    } catch (CharacterCodingException e) {
      return text;
    }
  }
}
