package io.github.ajdailey.qcaret.core.markdown;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * GitLab Flavored Markdown additions: heading anchors by GitLab's rules, the heading list,
 * autolinks, footnotes, alerts, front matter, image attributes, {@code [[_TOC_]]} and {@code >>>}.
 */
class GitLabMarkdownTest {
  private static List<String> lines(RenderedMarkdown r) {
    List<String> out = new ArrayList<String>();
    for (int i = 0; i < r.lineCount(); i++) {
      out.add(Render.line(r, i));
    }
    return out;
  }

  private static List<String> anchors(RenderedMarkdown r) {
    List<String> out = new ArrayList<String>();
    for (MarkdownHeading h : r.headings()) {
      out.add(h.anchor());
    }
    return out;
  }

  /** The examples of GitLab's Markdown documentation, "Heading IDs and links". */
  @Test
  void anchorsFollowGitLabsExamples() {
    RenderedMarkdown r =
        Render.of(
            "# This heading has spaces in it\n"
                + "## This heading has a :thumbsup: in it\n"
                + "# This heading has Unicode in it: 한글\n"
                + "## This heading has spaces in it\n"
                + "### This heading has spaces in it\n"
                + "## This heading has 3.5 in it (and parentheses)\n"
                + "## This heading has  multiple   spaces and --- hyphens\n");
    assertEquals(
        List.of(
            "this-heading-has-spaces-in-it",
            "this-heading-has-a-thumbsup-in-it",
            "this-heading-has-unicode-in-it-한글",
            "this-heading-has-spaces-in-it-1",
            "this-heading-has-spaces-in-it-2",
            "this-heading-has-35-in-it-and-parentheses",
            "this-heading-has-multiple-spaces-and-hyphens"),
        anchors(r));
  }

  @Test
  void hyphenRunsJoinAsGitLabJoinsThem() {
    assertEquals("a-b", HeadingAnchors.id("a  b"));
  }

  @Test
  void anchorsDropHtmlAndKeepUnderscoresAndCodeText() {
    RenderedMarkdown r = Render.of("## Use `MOCK_KEY` <b>now</b>!\n");
    assertEquals(List.of("use-mock_key-now"), anchors(r));
    assertEquals("Use MOCK_KEY now!", r.headings().get(0).text());
  }

  @Test
  void headingListHoldsAtxAndSetextHeadingsButNoneFromCode() {
    RenderedMarkdown r =
        Render.of("# One\n\nTwo\n---\n\n```\n# not a heading\n```\n\n> ### Three\n");
    List<MarkdownHeading> headings = r.headings();
    assertEquals(3, headings.size());
    assertEquals(new MarkdownHeading(1, "One", "one", 0, 0), headings.get(0));
    assertEquals(new MarkdownHeading(2, "Two", "two", 2, 2), headings.get(1));
    MarkdownHeading three = headings.get(2);
    assertEquals(3, three.level());
    assertEquals(9, three.sourceLine());
    assertEquals("Three", Render.line(r, three.line()));
    assertEquals(three.line(), r.headingLine("three"));
    assertEquals(-1, r.headingLine("missing"));
  }

  @Test
  void tocListsTheHeadingListAsLinksToTheirAnchors() {
    for (String marker : new String[] {"[[_TOC_]]", "[TOC]"}) {
      RenderedMarkdown r = Render.of(marker + "\n\n# A\n\n## B b\n\n# A\n");
      assertEquals(List.of("A", "B b", "A", "", "A", "", "B b", "", "A"), lines(r), marker);
      assertEquals(1, r.listDepth(0));
      assertEquals(2, r.listDepth(1));
      assertEquals(RenderedMarkdown.ListMarker.BULLET, r.marker(1));
      List<String> targets = new ArrayList<String>();
      for (MarkdownLink link : r.links()) {
        assertEquals(LinkTarget.Kind.ANCHOR, link.target().kind());
        targets.add(link.target().anchor());
      }
      assertEquals(anchors(r), targets);
      assertEquals(List.of("a", "b-b", "a-1"), targets);
      assertEquals(MarkdownBlock.Kind.TOC, r.blocks().get(0).kind());
      assertEquals(3, r.blocks().get(0).lineCount());
    }
  }

  @Test
  void aTocAfterABlockKeepsItsFirstMarkerOffTheSeparator() {
    RenderedMarkdown r = Render.of("# A\n\npara\n\n[[_TOC_]]\n");
    assertEquals(List.of("A", "", "para", "", "A"), lines(r));
    assertEquals(0, r.listDepth(3));
    assertEquals(RenderedMarkdown.ListMarker.NONE, r.marker(3));
    assertEquals(1, r.listDepth(4));
    assertEquals(RenderedMarkdown.ListMarker.BULLET, r.marker(4));
  }

  @Test
  void tocTextInsideAParagraphStaysText() {
    RenderedMarkdown r = Render.of("See [TOC] here\n");
    assertEquals("See [TOC] here", r.text());
  }

  @Test
  void aMultilineQuoteHoldsBlocksUpToTheClosingFence() {
    RenderedMarkdown r = Render.of(">>>\nFirst\n\n- item\n>>>\n\nAfter\n");
    assertEquals(List.of("First", "", "item", "", "After"), lines(r));
    assertEquals(1, r.quoteDepth(0));
    assertEquals(1, r.quoteDepth(2));
    assertEquals(1, r.listDepth(2));
    assertEquals(0, r.quoteDepth(4));
  }

  @Test
  void autolinksBecomeUrlLinks() {
    RenderedMarkdown r =
        Render.of("Go to https://example.com or www.example.org or mock@example.com");
    assertEquals(3, r.links().size());
    assertEquals("http://www.example.org", r.links().get(1).target().destination());
    assertEquals("mailto:mock@example.com", r.links().get(2).target().destination());
    for (MarkdownLink link : r.links()) {
      assertEquals(LinkTarget.Kind.URL, link.target().kind());
    }
  }

  @Test
  void footnotesAreNumberedByFirstReferenceAndRenderedAtTheEnd() {
    RenderedMarkdown r =
        Render.of(
            "A[^b] and B[^a] and A again[^b].\n\n[^a]: Note a.\n[^b]: Note b.\n[^c]: Unused.\n");
    assertEquals(List.of("A1 and B2 and A again1.", "", "", "Note b.", "Note a."), lines(r));
    assertTrue(r.rule(2));
    assertEquals(3, r.footnoteReferences().size());
    assertEquals(2, r.footnoteReferences().get(1).number());
    assertEquals(new MarkdownFootnote("b", 1, 3), r.footnotes().get(0));
    assertEquals(new MarkdownFootnote("a", 2, 4), r.footnotes().get(1));
    assertEquals(RenderedMarkdown.ListMarker.NUMBER, r.marker(3));
    assertEquals(2, r.number(4));
    MarkdownFootnote.Reference first = r.footnoteReferences().get(0);
    assertEquals("1", r.text().substring(first.start(), first.end()));
    assertEquals(InlineStyle.SUP, Render.first(r, InlineStyle.SUP).style());
  }

  @Test
  void alertsHaveAKindAndATitleLine() {
    RenderedMarkdown r = Render.of("> [!NOTE]\n> Body.\n\n> [!warning] Mind the gap\n> Text.\n");
    assertEquals(List.of("Note", "Body.", "", "Mind the gap", "Text."), lines(r));
    assertEquals(new MarkdownAlert(0, 2, "note"), r.alerts().get(0));
    assertEquals(new MarkdownAlert(3, 2, "warning"), r.alerts().get(1));
    assertEquals(1, r.quoteDepth(1));
    assertEquals("Note", Render.covered(r, Render.first(r, InlineStyle.STRONG)));
    assertEquals(MarkdownBlock.Kind.ALERT_TITLE, r.blocks().get(0).kind());
  }

  @Test
  void frontMatterIsAShadedYamlBlock() {
    RenderedMarkdown r = Render.of("---\ntitle: Mock\ntags: [a]\n---\n# Body\n");
    CodeBlock front = r.frontMatter();
    assertNotNull(front);
    assertEquals("yaml", front.language());
    assertEquals(0, front.firstLine());
    assertEquals(2, front.lineCount());
    assertEquals(List.of("title: Mock", "tags: [a]", "", "Body"), lines(r));
    assertTrue(r.code(0));
    assertEquals(1, r.sourceLine(0));
    assertNull(Render.of("# none\n").frontMatter());
  }

  @Test
  void imageAttributesCarryWidthAndHeight() {
    RenderedMarkdown r = Render.of("![a](x.png){width=100 height=50%}", Render.folder());
    MarkdownImage image = r.images().get(0);
    assertEquals("100", image.width());
    assertEquals("50%", image.height());
    assertEquals(String.valueOf(MarkdownImage.PLACEHOLDER), r.text());
    assertNull(Render.of("![a](x.png)", Render.folder()).images().get(0).width());
  }
}
