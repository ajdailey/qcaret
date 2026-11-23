package io.github.ajdailey.qcaret.app.editor;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.ajdailey.qcaret.core.markdown.RenderedMarkdown;
import org.junit.jupiter.api.Test;

/** {@link BlockSpacing} over headings, code blocks, a rule and blocks without a separator. */
class BlockSpacingTest {
  @Test
  void headingsAndCodeBlocksPadTheirSeparators() {
    RenderedMarkdown r =
        TableWrapTest.render(
            String.join(
                "\n",
                "Intro.",
                "",
                "## Two",
                "",
                "```",
                "code",
                "```",
                "",
                "### Three",
                "",
                "Last.",
                ""));
    int two = r.headings().get(0).line();
    int three = r.headings().get(1).line();
    int code = r.codeBlocks().get(0).firstLine();
    BlockSpacing spacing = BlockSpacing.of(r);
    assertArrayEquals(new int[] {two - 1, two + 1, code + 1}, spacing.lines());
    assertEquals(BlockSpacing.HEADING_ABOVE, spacing.below(two - 1));
    assertEquals(0, spacing.above(two - 1));
    assertEquals(BlockSpacing.RULE_GAP, spacing.above(two + 1), "the h2 rule gap");
    assertEquals(BlockSpacing.CODE_PADDING, spacing.below(code - 1), "shared with the gap");
    assertEquals(BlockSpacing.CODE_PADDING, spacing.above(code + 1));
    assertEquals(BlockSpacing.HEADING_ABOVE, spacing.below(three - 1), "after the code");
    assertEquals(0, spacing.above(three + 1), "no rule under h3");
    assertEquals(0, spacing.below(three + 1));
  }

  @Test
  void aBlockWithoutASeparatorAndARuleTakeNoSpace() {
    RenderedMarkdown r = TableWrapTest.render("```\ncode\n```\n\n---\n\n# One\n");
    int rule = 0;
    while (!r.rule(rule)) {
      rule++;
    }
    BlockSpacing spacing = BlockSpacing.of(r);
    assertEquals(BlockSpacing.CODE_PADDING, spacing.above(1));
    assertEquals(0, spacing.below(rule), "a rule line is a block, not a separator");
    assertEquals(BlockSpacing.HEADING_ABOVE, spacing.below(rule + 1));
    assertArrayEquals(new int[] {1, rule + 1}, spacing.lines());
    assertArrayEquals(new int[0], BlockSpacing.none().lines());
  }
}
