package io.github.ajdailey.qcaret.core.markdown;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link HeadingPath}, and {@link MarkdownTree#headingSourceLine} finding a heading by the id the
 * preview gives it.
 */
class HeadingPathTest {
  @TempDir Path temp;

  @Test
  void theTextAfterTheLastHashIsTheHeadingDecodedOnce() {
    Path folder = temp.resolve("a#b");
    assertEquals(
        new HeadingPath(folder.resolve("notes.md"), "set up"),
        HeadingPath.split(folder.resolve("notes.md#set%20up")));
    assertEquals(
        new HeadingPath(temp.resolve("x#y.md"), "z%41"),
        HeadingPath.split(temp.resolve("x#y.md#z%2541")));
    assertNull(HeadingPath.split(temp.resolve("#only")).heading());
    assertNull(HeadingPath.split(temp.resolve("trailing#")).heading());
    assertNull(HeadingPath.heading(""));
  }

  @Test
  void anExistingFileKeepsItsHash() throws Exception {
    Path whole = Files.writeString(temp.resolve("n.md#1"), "x", StandardCharsets.UTF_8);
    Files.writeString(temp.resolve("n.md"), "y", StandardCharsets.UTF_8);
    assertEquals(new HeadingPath(whole, null), HeadingPath.resolve(whole));
    Files.delete(whole);
    assertEquals(new HeadingPath(temp.resolve("n.md"), "1"), HeadingPath.resolve(whole));
    assertEquals(whole, HeadingPath.whole(temp.resolve("n.md"), "1"));
  }

  @Test
  void aMissingFileBeforeTheHashKeepsTheWholeName() {
    Path given = temp.resolve("absent.md#part");
    assertEquals(new HeadingPath(given, null), HeadingPath.resolve(given));
  }

  @Test
  void headingsAreFoundByThePreviewsIds() {
    String text =
        "# Intro\n\nText\n\n## Intro\n\nSetext Title!\n---\n\n```\n# Not a heading\n```\n\n"
            + "### Café  &  `Code`\n\n> # Quoted\n";
    MarkdownTree tree = new MarkdownRenderer().parse(text, new CancellationToken());
    assertEquals(0, tree.headingSourceLine("intro"));
    assertEquals(4, tree.headingSourceLine("intro-1"));
    assertEquals(6, tree.headingSourceLine("setext-title"));
    assertEquals(-1, tree.headingSourceLine("not-a-heading"));
    assertEquals(13, tree.headingSourceLine("café-code"));
    assertEquals(15, tree.headingSourceLine("quoted"));
    assertEquals(-1, tree.headingSourceLine("Intro"));
    RenderedMarkdown rendered = new MarkdownRenderer().render(tree, null, new CancellationToken());
    for (MarkdownHeading h : rendered.headings()) {
      assertEquals(h.sourceLine(), tree.headingSourceLine(h.anchor()), h.anchor());
    }
  }
}
