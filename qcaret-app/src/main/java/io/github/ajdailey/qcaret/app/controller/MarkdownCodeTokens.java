package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.editor.TokenCache;
import io.github.ajdailey.qcaret.core.buffer.RopeStorage;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.WorkerThread;
import io.github.ajdailey.qcaret.core.markdown.CodeBlock;
import io.github.ajdailey.qcaret.core.markdown.RenderedMarkdown;
import io.github.ajdailey.qcaret.core.syntax.GrammarSet;
import io.github.ajdailey.qcaret.core.syntax.Syntax;
import io.github.ajdailey.qcaret.core.syntax.SyntaxEngine;
import java.util.ArrayList;
import java.util.List;

/**
 * Colors a render's code blocks: each block's language names a grammar through {@link
 * GrammarSet#forLabel}, and a {@link SyntaxEngine} over the block's text tokenizes every line from
 * the grammar's initial state. Blocks are independent: a comment left open in one does not reach
 * the next.
 */
final class MarkdownCodeTokens {
  private MarkdownCodeTokens() {}

  /**
   * Computes the tokens of every code block.
   *
   * @param rendered the render
   * @param grammars the grammars, or null to leave every block plain
   * @param maxLineChars a longer line stays plain
   * @param cancel checked before each block and while tokenizing
   * @return one {@code start, end, TokenRole ordinal} array per block, rendered offsets
   * @throws java.util.concurrent.CancellationException when canceled
   */
  @WorkerThread
  static List<int[]> of(
      RenderedMarkdown rendered, GrammarSet grammars, int maxLineChars, CancellationToken cancel) {
    List<int[]> out = new ArrayList<int[]>(rendered.codeBlocks().size());
    for (CodeBlock block : rendered.codeBlocks()) {
      cancel.check();
      Syntax syntax = syntax(block.language(), grammars);
      out.add(syntax == null ? new int[0] : tokens(rendered, block, syntax, maxLineChars, cancel));
    }
    return out;
  }

  private static Syntax syntax(String language, GrammarSet grammars) {
    if (grammars == null || language == null) {
      return null;
    }
    String id = grammars.forLabel(language);
    return id == null || GrammarSet.PLAIN.equals(id) ? null : grammars.syntax(id);
  }

  @WorkerThread
  private static int[] tokens(
      RenderedMarkdown rendered,
      CodeBlock block,
      Syntax syntax,
      int maxLineChars,
      CancellationToken cancel) {
    int last = block.firstLine() + block.lineCount() - 1;
    int start = rendered.lineStart(block.firstLine());
    int end =
        last + 1 < rendered.lineCount()
            ? rendered.lineStart(last + 1) - 1
            : rendered.text().length();
    SyntaxEngine engine =
        new SyntaxEngine(
            syntax, RopeStorage.of(rendered.text().substring(start, end)).snapshot(), maxLineChars);
    engine.request(0, block.lineCount() - 1);
    SyntaxEngine.Slice slice = engine.run(System.nanoTime(), cancel);
    int count = 0;
    for (int[] line : slice.tokens()) {
      count += line.length;
    }
    int[] out = new int[count];
    int at = 0;
    for (int i = 0; i < slice.lines().length; i++) {
      int offset = rendered.lineStart(block.firstLine() + slice.lines()[i]);
      int[] line = slice.tokens()[i];
      for (int t = 0; t < line.length; t += 3) {
        out[at++] = offset + line[t];
        out[at++] = offset + line[t + 1];
        out[at++] = TokenCache.role(line[t + 2]);
      }
    }
    return out;
  }

  /**
   * Computes the tokens of one code block's text.
   *
   * @param text the block's text, LF breaks
   * @param language the info string's language, or null
   * @param grammars the grammars, or null to leave the text plain
   * @param maxLineChars a longer line stays plain
   * @param cancel checked while tokenizing
   * @return {@code start, end, TokenRole ordinal} triples in offsets of {@code text}, ascending
   * @throws java.util.concurrent.CancellationException when canceled
   */
  @WorkerThread
  static int[] ofText(
      String text,
      String language,
      GrammarSet grammars,
      int maxLineChars,
      CancellationToken cancel) {
    Syntax syntax = syntax(language, grammars);
    if (syntax == null || text.isEmpty()) {
      return new int[0];
    }
    List<Integer> starts = new ArrayList<Integer>();
    starts.add(0);
    for (int i = 0; i < text.length(); i++) {
      if (text.charAt(i) == '\n') {
        starts.add(i + 1);
      }
    }
    SyntaxEngine engine = new SyntaxEngine(syntax, RopeStorage.of(text).snapshot(), maxLineChars);
    engine.request(0, starts.size() - 1);
    SyntaxEngine.Slice slice = engine.run(System.nanoTime(), cancel);
    int count = 0;
    for (int[] line : slice.tokens()) {
      count += line.length;
    }
    int[] out = new int[count];
    int at = 0;
    for (int i = 0; i < slice.lines().length; i++) {
      int offset = starts.get(slice.lines()[i]);
      int[] line = slice.tokens()[i];
      for (int t = 0; t < line.length; t += 3) {
        out[at++] = offset + line[t];
        out[at++] = offset + line[t + 1];
        out[at++] = TokenCache.role(line[t + 2]);
      }
    }
    return out;
  }
}
