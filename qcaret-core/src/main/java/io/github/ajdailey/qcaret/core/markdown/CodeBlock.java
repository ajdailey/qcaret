package io.github.ajdailey.qcaret.core.markdown;

/**
 * A fenced or indented code block's rendered lines, one per source line, shaded. Coloring by the
 * language belongs to the caller, which maps {@link #language} to a grammar.
 *
 * @param firstLine the rendered line of the block's first code line
 * @param lineCount the code lines, at least 1
 * @param language the info string's first word without {@code {}, {@code .} or {@code }}, such as
 *     {@code java} for {@code ```{.java title=x}}; {@code yaml} for front matter; null for an
 *     indented block or an empty info string
 */
public record CodeBlock(int firstLine, int lineCount, String language) {}
