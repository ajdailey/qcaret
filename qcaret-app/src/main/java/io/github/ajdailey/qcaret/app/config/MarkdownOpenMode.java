package io.github.ajdailey.qcaret.app.config;

import java.util.Locale;

/** {@code markdown.openMode}: the view mode of a Markdown file opened with no saved mode. */
public enum MarkdownOpenMode {
  /** {@code source}: the editor only. */
  SOURCE,
  /** {@code split}: the editor and the preview. */
  SPLIT,
  /** {@code preview}: the preview only. */
  PREVIEW;

  /**
   * Returns the value as {@code qcaret.conf} holds it.
   *
   * @return {@code source}, {@code split} or {@code preview}
   */
  public String key() {
    return name().toLowerCase(Locale.ROOT);
  }

  /**
   * Parses a stored value.
   *
   * @param value the value, or null
   * @return the choice; null for any other value
   */
  public static MarkdownOpenMode parse(String value) {
    if (value == null) {
      return null;
    }
    for (MarkdownOpenMode choice : values()) {
      if (choice.key().equals(value.strip())) {
        return choice;
      }
    }
    return null;
  }
}
