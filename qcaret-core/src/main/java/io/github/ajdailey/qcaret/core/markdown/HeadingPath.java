package io.github.ajdailey.qcaret.core.markdown;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/**
 * A file path and the heading a {@code notes.md#setup} name selects. {@code #} is legal in a file
 * name, so a path that names an existing file keeps its {@code #}.
 *
 * @param path the file
 * @param heading the fragment after the last {@code #} of the file name, percent-decoded once
 *     ({@link LinkTarget}); null when none applies
 */
public record HeadingPath(Path path, String heading) {
  /**
   * Splits a heading off a path's file name, at its last {@code #}. A name starting with {@code #}
   * or ending with it has no heading. Reads no file.
   *
   * @param given the path as given
   * @return the file before the {@code #} and the heading; {@code given} with no heading otherwise
   */
  public static HeadingPath split(Path given) {
    Path name = given.getFileName();
    String text = name == null ? "" : name.toString();
    int hash = text.lastIndexOf('#');
    if (hash <= 0 || hash == text.length() - 1) {
      return new HeadingPath(given, null);
    }
    Path file;
    try {
      file = given.resolveSibling(text.substring(0, hash));
    } catch (InvalidPathException e) {
      return new HeadingPath(given, null);
    }
    return new HeadingPath(file, LinkTarget.decode(text.substring(hash + 1)));
  }

  /**
   * Splits a heading off a path, as {@link #split} does, only when the path names no file and the
   * path before the {@code #} names a regular file. Reads the file system.
   *
   * @param given the path as given
   * @return the file and its heading, or {@code given} with no heading
   */
  public static HeadingPath resolve(Path given) {
    HeadingPath split = split(given);
    if (split.heading() == null || Files.exists(given) || !Files.isRegularFile(split.path())) {
      return new HeadingPath(given, null);
    }
    return split;
  }

  /**
   * Decodes a fragment as a heading id: {@code %XX} escapes as UTF-8, once ({@link LinkTarget}).
   *
   * @param fragment the text after {@code #}
   * @return the id; null for an empty fragment
   */
  public static String heading(String fragment) {
    return fragment.isEmpty() ? null : LinkTarget.decode(fragment);
  }

  /**
   * Returns the file a link's path and fragment name together, the file {@link #resolve} keeps
   * whole.
   *
   * @param path the file before the {@code #}
   * @param heading the fragment
   * @return {@code path} with {@code #heading} appended to its name, or null when the file system
   *     refuses the name
   */
  public static Path whole(Path path, String heading) {
    Path name = path.getFileName();
    if (name == null) {
      return null;
    }
    try {
      return path.resolveSibling(name + "#" + heading);
    } catch (InvalidPathException e) {
      return null;
    }
  }
}
