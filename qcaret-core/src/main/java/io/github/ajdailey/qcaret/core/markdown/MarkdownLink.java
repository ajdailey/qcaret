package io.github.ajdailey.qcaret.core.markdown;

/**
 * A link's rendered text and target.
 *
 * @param start the first rendered char
 * @param end the char after the last
 * @param target the target
 */
public record MarkdownLink(int start, int end, LinkTarget target) {}
