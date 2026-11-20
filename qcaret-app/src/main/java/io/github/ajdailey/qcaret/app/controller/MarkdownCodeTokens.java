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
      Syntax syntax = syntax(block, grammars);
      out.add(syntax == null ? new int[0] : tokens(rendered, block, syntax, maxLineChars, cancel));
    }
    return out;
  }

  private static Syntax syntax(CodeBlock block, GrammarSet grammars) {
    if (grammars == null || block.language() == null) {
      return null;
    }
    String id = grammars.forLabel(block.language());
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
}
