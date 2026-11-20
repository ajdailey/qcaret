package io.github.ajdailey.qcaret.core.markdown;

/**
 * An image. A {@link LinkTarget.Kind#FILE} target with a path renders as one {@link #PLACEHOLDER}
 * char, the space the image takes; any other target renders as the alt text followed by the
 * destination in parentheses.
 *
 * @param start the first rendered char
 * @param end the char after the last
 * @param alt the alt text, plain
 * @param target the source
 * @param width the {@code {width=...}} attribute as written, such as {@code 100}, {@code 100px} or
 *     {@code 50%}; null without one
 * @param height the {@code {height=...}} attribute as written; null without one
 */
public record MarkdownImage(
    int start, int end, String alt, LinkTarget target, String width, String height) {
  /** The char a drawable image occupies. */
  public static final char PLACEHOLDER = '￼';

  /**
   * Reports whether the image renders as {@link #PLACEHOLDER}.
   *
   * @return true for a local file with a path
   */
  public boolean drawable() {
    return drawable(target);
  }

  static boolean drawable(LinkTarget target) {
    return target.kind() == LinkTarget.Kind.FILE && target.path() != null;
  }
}
