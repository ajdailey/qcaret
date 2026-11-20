package io.github.ajdailey.qcaret.core.markdown;

/**
 * An ATX or setext heading. {@link RenderedMarkdown#headings} lists them in document order, and a
 * {@code [[_TOC_]]} lists the same headings with the same anchors.
 *
 * @param level 1 to 6
 * @param text the heading's text without markup or HTML tags
 * @param anchor the id an in-document link names it by, by GitLab's rules: lower case; letters,
 *     digits, {@code _} and {@code -} kept; spaces as {@code -}; other chars dropped; a run of
 *     {@code -} as one; {@code -1}, {@code -2} appended to repeats
 * @param sourceLine the zero-based source line it starts on
 * @param line the rendered line
 */
public record MarkdownHeading(int level, String text, String anchor, int sourceLine, int line) {}
