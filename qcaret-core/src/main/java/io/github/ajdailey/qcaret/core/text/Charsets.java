package io.github.ajdailey.qcaret.core.text;

import java.nio.charset.Charset;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Resolves charset names: the one class that calls {@link Charset#forName} and {@link
 * Charset#availableCharsets}. {@code windows-1252} and each of its aliases resolve to {@link
 * Windows1252}; every other name resolves as the JDK resolves it.
 */
public final class Charsets {
  private Charsets() {}

  /**
   * Returns the charset a name or alias names.
   *
   * @param name a charset name or alias
   * @return {@link Windows1252#INSTANCE} for {@code windows-1252}, else the JDK charset
   * @throws java.nio.charset.IllegalCharsetNameException when the name is not legal
   * @throws java.nio.charset.UnsupportedCharsetException when the runtime has no such charset
   * @throws IllegalArgumentException when the name is null
   */
  public static Charset forName(String name) {
    return Windows1252.substitute(jdk(name));
  }

  /**
   * Returns every charset the runtime supports, keyed and ordered by canonical name, with {@link
   * Windows1252} for {@code windows-1252}. Loads every charset provider.
   *
   * @return a new map
   */
  public static SortedMap<String, Charset> available() {
    SortedMap<String, Charset> all = new TreeMap<String, Charset>(String.CASE_INSENSITIVE_ORDER);
    for (Map.Entry<String, Charset> entry : Charset.availableCharsets().entrySet()) {
      all.put(entry.getKey(), Windows1252.substitute(entry.getValue()));
    }
    return all;
  }

  /** Returns the JDK's own charset for a name, for {@link Windows1252} to build on. */
  static Charset jdk(String name) {
    return Charset.forName(name);
  }
}
