package io.github.ajdailey.qcaret.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.ajdailey.qcaret.app.view.OutlineEntry;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.markdown.MarkdownHeading;
import io.github.ajdailey.qcaret.core.markdown.MarkdownRenderer;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link OutlineTree} over real renders: skipped levels, setext headings, {@code #} lines inside
 * code, duplicates, the entry and text limits, and the caret's entry.
 */
class OutlineTreeTest {
  private static List<OutlineEntry> outline(String markdown) {
    return OutlineTree.of(
        new MarkdownRenderer().render(markdown, null, new CancellationToken()).headings());
  }

  @Test
  void aSkippedLevelNestsOneStepAndALowerLevelClimbs() {
    List<OutlineEntry> entries = outline("# A\n\n### B\n\n## C\n\n#### D\n\n# E\n");
    assertEquals(
        List.of(
            new OutlineEntry("A", 1, -1, 0),
            new OutlineEntry("B", 3, 0, 2),
            new OutlineEntry("C", 2, 0, 4),
            new OutlineEntry("D", 4, 2, 6),
            new OutlineEntry("E", 1, -1, 8)),
        entries);
  }

  @Test
  void headingsBeforeTheFirstTopLevelOneAreTopEntries() {
    List<OutlineEntry> entries = outline("## Intro\n\n# Title\n\n## Part\n");
    assertEquals(-1, entries.get(0).parent());
    assertEquals(-1, entries.get(1).parent());
    assertEquals(1, entries.get(2).parent());
  }

  @Test
  void setextHeadingsAreListedAndCodeLinesAreNot() {
    String text =
        "Title\n=====\n\n```\n# not a heading\n```\n\n    # indented code\n\nPart\n----\n\n"
            + "> # Quoted\n\n- # In a list\n";
    List<OutlineEntry> entries = outline(text);
    List<String> names = new ArrayList<String>();
    for (OutlineEntry e : entries) {
      names.add(e.text() + "@" + e.sourceLine() + "/" + e.level());
    }
    assertEquals(List.of("Title@0/1", "Part@9/2", "Quoted@12/1", "In a list@14/1"), names);
  }

  @Test
  void duplicatesAreSeparateEntriesWithTheirOwnLines() {
    List<OutlineEntry> entries = outline("# Notes\n\n## Mock\n\n# Notes\n\n## Mock\n");
    assertEquals(4, entries.size());
    assertEquals(new OutlineEntry("Mock", 2, 0, 2), entries.get(1));
    assertEquals(new OutlineEntry("Mock", 2, 2, 6), entries.get(3));
  }

  @Test
  void entriesAndTextAreCut() {
    List<MarkdownHeading> headings = new ArrayList<MarkdownHeading>();
    for (int i = 0; i < OutlineTree.MAX_ENTRIES + 5; i++) {
      headings.add(new MarkdownHeading(1, "h" + i, "h" + i, i, i));
    }
    assertEquals(OutlineTree.MAX_ENTRIES, OutlineTree.of(headings).size());
    String longText = "x".repeat(OutlineTree.TEXT_CHARS - 1) + "😀tail";
    OutlineEntry cut = OutlineTree.of(List.of(new MarkdownHeading(2, longText, "a", 0, 0))).get(0);
    assertEquals(OutlineTree.TEXT_CHARS - 1, cut.text().length());
  }

  @Test
  void theCaretsEntryIsTheLastHeadingAtOrAboveItsLine() {
    List<OutlineEntry> entries = outline("text\n\n# A\n\nbody\n\n## B\n");
    assertEquals(-1, OutlineTree.at(entries, 0));
    assertEquals(0, OutlineTree.at(entries, 2));
    assertEquals(0, OutlineTree.at(entries, 5));
    assertEquals(1, OutlineTree.at(entries, 6));
    assertEquals(1, OutlineTree.at(entries, 100));
    assertEquals(-1, OutlineTree.at(List.<OutlineEntry>of(), 3));
  }
}
