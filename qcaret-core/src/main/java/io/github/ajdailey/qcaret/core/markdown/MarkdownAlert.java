package io.github.ajdailey.qcaret.core.markdown;

/**
 * An alert ({@code > [!note]}): a block quote whose first line is its title, drawn as a {@link
 * InlineStyle#STRONG} run. Its lines carry the quote depth of a block quote.
 *
 * @param firstLine the rendered title line
 * @param lineCount the lines, the title included
 * @param kind {@code note}, {@code tip}, {@code important}, {@code warning} or {@code caution}
 */
public record MarkdownAlert(int firstLine, int lineCount, String kind) {}
