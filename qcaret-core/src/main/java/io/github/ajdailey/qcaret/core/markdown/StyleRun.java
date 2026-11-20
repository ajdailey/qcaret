package io.github.ajdailey.qcaret.core.markdown;

/**
 * A style over a range of rendered text. Runs nest and overlap: strong text inside a link has a
 * {@link InlineStyle#LINK} run and a {@link InlineStyle#STRONG} run.
 *
 * @param start the first char, a UTF-16 offset into {@link RenderedMarkdown#text}
 * @param end the char after the last
 * @param style the style
 * @param level the heading level, 1 to 6, for {@link InlineStyle#HEADING}; 0 otherwise
 */
public record StyleRun(int start, int end, InlineStyle style, int level) {}
