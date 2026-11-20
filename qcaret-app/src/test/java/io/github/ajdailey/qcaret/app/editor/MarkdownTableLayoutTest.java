package io.github.ajdailey.qcaret.app.editor;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.markdown.MarkdownRenderer;
import io.github.ajdailey.qcaret.core.markdown.RenderedMarkdown;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/**
 * {@link MarkdownTableLayout} over a fake measurer that gives each char 10 pixels, 12 when strong:
 * column widths, alignment, header measuring and bounded measuring.
 */
class MarkdownTableLayoutTest {
  private static final int PADDING = 4;

  /** Ten pixels a char, twelve in a strong row; records each call. */
  private static final class Fake implements TextMeasurer {
    final List<String> measured = new ArrayList<String>();

    @Override
    public int width(String text, boolean strong) {
      measured.add((strong ? "!" : "") + text);
      return text.length() * (strong ? 12 : 10);
    }
  }

  private static MarkdownTableLayout layout(RenderedMarkdown r) {
    return new MarkdownTableLayout(r.tables().get(0), r.text(), PADDING);
  }

  private static RenderedMarkdown render(String text) {
    return new MarkdownRenderer().render(text, null, new CancellationToken());
  }

  @Test
  void columnsFitTheWidestCellAndAlignmentPlacesEachCell() {
    RenderedMarkdown r = render("| L | Center | R |\n|:--|:-:|--:|\n| aaaa | b | c |\n");
    MarkdownTableLayout layout = layout(r);
    Fake fake = new Fake();
    layout.measure(fake, Integer.MAX_VALUE);
    assertTrue(layout.measured());
    assertEquals(List.of("!L", "!Center", "!R", "aaaa", "b", "c"), fake.measured);
    // Inner widths: max(12, 40) = 40, max(72, 10) = 72, max(12, 10) = 12.
    assertArrayEquals(new int[] {0, 48, 128, 148}, layout.edges());
    MarkdownTableLayout.Row header = layout.row(0);
    assertEquals(PADDING, header.indent());
    assertArrayEquals(new int[] {48 + PADDING, 128 + PADDING}, header.tabStops());
    MarkdownTableLayout.Row body = layout.row(1);
    assertEquals(PADDING, body.indent());
    assertArrayEquals(new int[] {48 + PADDING + 31, 128 + PADDING + 2}, body.tabStops());
  }

  @Test
  void measuringStopsAfterTheCellBudgetAtARowEnd() {
    StringBuilder text = new StringBuilder("| a | b |\n|---|---|\n");
    for (int i = 0; i < 9; i++) {
      text.append("| x | y |\n");
    }
    final MarkdownTableLayout layout = layout(render(text.toString()));
    Fake fake = new Fake();
    assertEquals(4, layout.measure(fake, 3));
    assertFalse(layout.measured());
    assertThrows(
        IllegalStateException.class,
        new Executable() {
          @Override
          public void execute() {
            layout.edges();
          }
        });
    int turns = 1;
    while (!layout.measured()) {
      layout.measure(fake, 3);
      turns++;
    }
    assertEquals(5, turns);
    assertEquals(20, fake.measured.size());
  }

  @Test
  void aShortRowLeavesItsMissingCellsEmpty() {
    RenderedMarkdown r = render("| a | bb |\n|---|---|\n| ccc |\n");
    MarkdownTableLayout layout = layout(r);
    layout.measure(new Fake(), Integer.MAX_VALUE);
    assertArrayEquals(new int[] {0, 38, 70}, layout.edges());
    assertEquals(PADDING, layout.row(1).indent());
  }
}
