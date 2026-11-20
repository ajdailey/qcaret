package io.github.ajdailey.qcaret.app.print;

import io.github.ajdailey.qcaret.core.buffer.TextSnapshot;
import io.github.ajdailey.qcaret.core.syntax.GrammarSet;
import java.nio.file.Path;

/**
 * What a rendered Markdown print job reads, captured when the command runs.
 *
 * @param name the document's name, for the header
 * @param text the Markdown source
 * @param folder the document's folder, against which relative image paths resolve; null for an
 *     untitled document
 * @param grammars the grammars code blocks are colored with, or null to leave them plain
 * @param maxLineChars a longer code line stays plain
 */
public record MarkdownPrintSource(
    String name, TextSnapshot text, Path folder, GrammarSet grammars, int maxLineChars) {}
