package io.github.ajdailey.qcaret.core.markdown;

import org.commonmark.node.Block;
import org.commonmark.node.CustomBlock;
import org.commonmark.parser.block.AbstractBlockParser;
import org.commonmark.parser.block.BlockContinue;
import org.commonmark.parser.block.BlockParserFactory;
import org.commonmark.parser.block.BlockStart;
import org.commonmark.parser.block.MatchedBlockParser;
import org.commonmark.parser.block.ParserState;

/**
 * GitLab's two block extensions, as commonmark block parsers.
 *
 * <ul>
 *   <li>A line of {@code >>>} opens a {@link MultilineQuote}, which holds every block up to the
 *       next {@code >>>} line, or to the end of the document or its container.
 *   <li>{@code [[_TOC_]]} or {@code [TOC]} alone on a line, indented under 4 columns, is a {@link
 *       Toc}.
 * </ul>
 */
final class GitLabBlocks {
  private GitLabBlocks() {}

  /** A {@code >>>} block quote. */
  static final class MultilineQuote extends CustomBlock {}

  /** A table of contents marker. */
  static final class Toc extends CustomBlock {}

  /** Starts both blocks. */
  static final BlockParserFactory FACTORY =
      new BlockParserFactory() {
        @Override
        public BlockStart tryStart(ParserState state, MatchedBlockParser matched) {
          if (state.getIndent() >= 4) {
            return BlockStart.none();
          }
          String rest = rest(state);
          int end = state.getLine().getContent().length();
          if (rest.equals(">>>")) {
            return BlockStart.of(new QuoteParser()).atIndex(end);
          }
          if (rest.equals("[[_TOC_]]") || rest.equals("[TOC]")) {
            return BlockStart.of(new TocParser()).atIndex(end);
          }
          return BlockStart.none();
        }
      };

  /** The line from the first non-space char, trailing spaces removed. */
  private static String rest(ParserState state) {
    CharSequence line = state.getLine().getContent();
    int start = Math.min(state.getNextNonSpaceIndex(), line.length());
    return line.subSequence(start, line.length()).toString().stripTrailing();
  }

  private static final class QuoteParser extends AbstractBlockParser {
    private final MultilineQuote block = new MultilineQuote();

    @Override
    public Block getBlock() {
      return block;
    }

    @Override
    public boolean isContainer() {
      return true;
    }

    @Override
    public boolean canContain(Block child) {
      return true;
    }

    @Override
    public BlockContinue tryContinue(ParserState state) {
      if (state.getIndent() < 4 && rest(state).equals(">>>")) {
        return BlockContinue.finished();
      }
      return BlockContinue.atIndex(state.getIndex());
    }
  }

  private static final class TocParser extends AbstractBlockParser {
    private final Toc block = new Toc();

    @Override
    public Block getBlock() {
      return block;
    }

    @Override
    public BlockContinue tryContinue(ParserState state) {
      return BlockContinue.none();
    }
  }
}
