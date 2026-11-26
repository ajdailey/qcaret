package io.github.ajdailey.qcaret.core.markdown;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import org.commonmark.node.CustomBlock;
import org.commonmark.node.Document;
import org.commonmark.node.Node;
import org.commonmark.node.Paragraph;
import org.commonmark.node.Text;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/** {@link MarkdownRenderer}: the text, runs, line attributes and records of each element. */
class MarkdownRendererTest {
  private static List<String> lines(RenderedMarkdown r) {
    List<String> out = new ArrayList<String>();
    for (int i = 0; i < r.lineCount(); i++) {
      out.add(Render.line(r, i));
    }
    return out;
  }

  @Test
  void headingsAndParagraphsAreSeparatedByOneEmptyLine() {
    RenderedMarkdown r = Render.of("# Title\n\nSome *soft*\nwrapped text.\n\nSetext\n===\n");
    assertEquals(List.of("Title", "", "Some soft wrapped text.", "", "Setext"), lines(r));
    StyleRun heading = Render.first(r, InlineStyle.HEADING);
    assertEquals("Title", Render.covered(r, heading));
    assertEquals(1, heading.level());
    assertEquals("soft", Render.covered(r, Render.first(r, InlineStyle.EMPHASIS)));
    assertEquals(List.of(0, 2, 4), blockLines(r));
  }

  @Test
  void inlineStylesCoverTheirText() {
    RenderedMarkdown r = Render.of("**b** *e* ~~s~~ `c` [l](https://example.com)");
    assertEquals("b e s c l", r.text());
    assertEquals("b", Render.covered(r, Render.first(r, InlineStyle.STRONG)));
    assertEquals("e", Render.covered(r, Render.first(r, InlineStyle.EMPHASIS)));
    assertEquals("s", Render.covered(r, Render.first(r, InlineStyle.STRIKETHROUGH)));
    assertEquals("c", Render.covered(r, Render.first(r, InlineStyle.CODE)));
    assertEquals("l", Render.covered(r, Render.first(r, InlineStyle.LINK)));
    assertEquals(LinkTarget.Kind.URL, r.links().get(0).target().kind());
  }

  @Test
  void controlCharsShowAsHexTextAndRangesSpanIt() {
    RenderedMarkdown r =
        Render.of(
            "# St\u0001eps\n\n[li\u001bnk](x) **b\u007f**\n\n| a\u0085 | b |\n|---|---|\n"
                + "| c | d |\n\n```\nx\u001by\n```\n");
    assertEquals("St<01>eps", Render.line(r, 0));
    assertEquals("St<01>eps", Render.covered(r, Render.first(r, InlineStyle.HEADING)));
    assertEquals("St\u0001eps", r.headings().get(0).text());
    assertEquals("li<1B>nk", Render.covered(r, Render.first(r, InlineStyle.LINK)));
    MarkdownLink link = r.links().get(0);
    assertEquals("li<1B>nk", r.text().substring(link.start(), link.end()));
    assertEquals("b<7F>", Render.covered(r, Render.first(r, InlineStyle.STRONG)));
    int[] header = r.tables().get(0).cells()[0];
    assertEquals("a<85>", r.text().substring(header[0], header[1]));
    assertEquals("x<1B>y", Render.line(r, r.codeBlocks().get(0).firstLine()));
  }

  @Test
  void surrogateCharacterReferencesRenderAsTheReplacementChar() {
    RenderedMarkdown r = Render.of("# a &#xD800;\n\nb &#56320; &#x1F600;\n");
    assertEquals(List.of("a �", "", "b � 😀"), lines(r));
    assertEquals("a �", r.headings().get(0).text());
  }

  @Test
  void runsAreOrderedByStart() {
    RenderedMarkdown r = Render.of("a **b *c* d** e");
    int last = -1;
    for (StyleRun run : r.runs()) {
      assertTrue(run.start() >= last);
      last = run.start();
    }
  }

  @Test
  void htmlMapsBreaksKbdSubAndSupAndShowsOtherTagsAsSource() {
    RenderedMarkdown r =
        Render.of("Press <kbd>Ctrl</kbd>, H<sub>2</sub>O, x<sup>2</sup><br>next <span>s</span>");
    assertEquals(List.of("Press Ctrl, H2O, x2", "next <span>s</span>"), lines(r));
    assertEquals("Ctrl", Render.covered(r, Render.first(r, InlineStyle.KBD)));
    assertEquals("2", Render.covered(r, Render.first(r, InlineStyle.SUB)));
    assertEquals("2", Render.covered(r, Render.first(r, InlineStyle.SUP)));
    assertEquals(0, r.sourceLine(1));
  }

  @Test
  void aLineBreakInInlineHtmlRendersAsASpace() {
    RenderedMarkdown r = Render.of("x <span title=\"a\nb\">y</span>\n\n# H\n");
    assertEquals(List.of("x <span title=\"a b\">y</span>", "", "H"), lines(r));
    assertEquals(newlines(r) + 1, r.lineCount());
    assertEquals(2, r.headings().get(0).line());
    assertEquals(3, r.sourceLine(2));
  }

  @Test
  void lineIndexesAfterAMultiLinePictureTagLandOnTheirLines() {
    RenderedMarkdown r =
        Render.of(
            "<picture> <source media=\"(prefers-color-scheme: dark)\"\n"
                + "srcset=\"mock-dark.svg\"> <img\n"
                + "src=\"mock-light.svg\" alt=\"logo\" width=\"96\"\n"
                + "height=\"96\"> </picture>\n\n"
                + "| A | B |\n|---|---|\n| `c` | d |\n\n# After\n");
    assertEquals(newlines(r) + 1, r.lineCount());
    assertEquals(6, r.lineCount());
    assertEquals("A\tB", Render.line(r, 2));
    assertEquals(2, r.tables().get(0).firstLine());
    assertEquals(5, r.sourceLine(2));
    assertEquals(7, r.sourceLine(3));
    assertEquals(2, r.renderedLine(5));
    StyleRun code = Render.first(r, InlineStyle.CODE);
    assertEquals("c", Render.covered(r, code));
    assertEquals(r.lineStart(3), code.start());
    assertEquals(5, r.headings().get(0).line());
    assertEquals("After", Render.line(r, 5));
  }

  private static int newlines(RenderedMarkdown r) {
    int n = 0;
    for (int i = 0; i < r.text().length(); i++) {
      if (r.text().charAt(i) == '\n') {
        n++;
      }
    }
    return n;
  }

  @Test
  void anHtmlBlockShowsAsSourceText() {
    RenderedMarkdown r = Render.of("<div class=\"x\">\n<b>bold</b>\n</div>\n");
    assertEquals(List.of("<div class=\"x\">", "<b>bold</b>", "</div>"), lines(r));
    assertEquals(MarkdownBlock.Kind.HTML, r.blocks().get(0).kind());
    assertEquals(3, r.blocks().get(0).lineCount());
  }

  @Test
  void detailsShowExpandedWithABoldSummary() {
    RenderedMarkdown r =
        Render.of("<details>\n<summary>More</summary>\n\nBody\n\n</details>\n\nAfter\n");
    assertEquals(List.of("More", "", "Body", "", "After"), lines(r));
    assertEquals("More", Render.covered(r, Render.first(r, InlineStyle.STRONG)));
  }

  @Test
  void hardBreaksStartALineMappedToTheNextSourceLine() {
    RenderedMarkdown r = Render.of("one  \ntwo\\\nthree\n");
    assertEquals(List.of("one", "two", "three"), lines(r));
    assertEquals(0, r.sourceLine(0));
    assertEquals(1, r.sourceLine(1));
    assertEquals(2, r.sourceLine(2));
  }

  @Test
  void nestedListsCarryDepthMarkersAndNumbers() {
    RenderedMarkdown r = Render.of("- a\n  - b\n    continued  \n    more\n- c\n\n3. x\n4. y\n");
    assertEquals(List.of("a", "b continued", "more", "c", "", "x", "y"), lines(r));
    assertEquals(1, r.listDepth(0));
    assertEquals(RenderedMarkdown.ListMarker.BULLET, r.marker(0));
    assertEquals(2, r.listDepth(1));
    assertEquals(RenderedMarkdown.ListMarker.BULLET, r.marker(1));
    assertEquals(2, r.listDepth(2));
    assertEquals(RenderedMarkdown.ListMarker.NONE, r.marker(2));
    assertEquals(1, r.listDepth(3));
    assertEquals(0, r.listDepth(4));
    assertEquals(RenderedMarkdown.ListMarker.NUMBER, r.marker(5));
    assertEquals(3, r.number(5));
    assertEquals(4, r.number(6));
    assertEquals(0, r.number(0));
  }

  @Test
  void aLooseListSeparatesItsItems() {
    RenderedMarkdown r = Render.of("- a\n\n- b\n");
    assertEquals(List.of("a", "", "b"), lines(r));
    assertEquals(1, r.listDepth(1));
    assertEquals(RenderedMarkdown.ListMarker.NONE, r.marker(1));
    assertEquals(RenderedMarkdown.ListMarker.BULLET, r.marker(2));
  }

  @Test
  void anEmptyItemGetsAnEmptyLineWithItsMarker() {
    RenderedMarkdown r = Render.of("-\n- b\n");
    assertEquals(List.of("", "b"), lines(r));
    assertEquals(RenderedMarkdown.ListMarker.BULLET, r.marker(0));
    assertEquals(MarkdownBlock.Kind.EMPTY, r.blocks().get(0).kind());
  }

  @Test
  void taskItemsCarryTheirState() {
    RenderedMarkdown r = Render.of("- [x] done\n- [ ] open\n- plain\n");
    assertEquals(List.of("done", "open", "plain"), lines(r));
    assertEquals(RenderedMarkdown.TaskState.DONE, r.task(0));
    assertEquals(RenderedMarkdown.TaskState.OPEN, r.task(1));
    assertEquals(RenderedMarkdown.TaskState.NONE, r.task(2));
  }

  @Test
  void nestedQuotesCarryDepthOnEveryLineIncludingSeparators() {
    RenderedMarkdown r = Render.of("> a\n>\n> > b\n>\n> c\n");
    assertEquals(List.of("a", "", "b", "", "c"), lines(r));
    assertArrayEquals(
        new int[] {1, 1, 2, 1, 1},
        new int[] {
          r.quoteDepth(0), r.quoteDepth(1), r.quoteDepth(2), r.quoteDepth(3), r.quoteDepth(4)
        });
  }

  @Test
  void aQuoteInsideAListCarriesBothDepths() {
    RenderedMarkdown r = Render.of("- item\n\n  > quoted\n");
    int quoted = lines(r).indexOf("quoted");
    assertEquals(1, r.listDepth(quoted));
    assertEquals(1, r.quoteDepth(quoted));
  }

  @Test
  void thematicBreakIsAnEmptyRuleLine() {
    RenderedMarkdown r = Render.of("a\n\n---\n\nb\n");
    assertEquals(List.of("a", "", "", "", "b"), lines(r));
    assertTrue(r.rule(2));
    assertFalse(r.rule(1));
    assertEquals(MarkdownBlock.Kind.RULE, r.blocks().get(1).kind());
  }

  @Test
  void tablesKeepAlignmentCellRangesAndInlineStyles() {
    RenderedMarkdown r =
        Render.of("| A | **B** | C |\n|:--|:-:|--:|\n| 1 | `two` | 3 |\n| x\ty |\n");
    assertEquals(List.of("A\tB\tC", "1\ttwo\t3", "x y\t\t"), lines(r));
    MarkdownTable t = r.tables().get(0);
    assertEquals(0, t.firstLine());
    assertEquals(3, t.rowCount());
    assertEquals(
        List.of(
            MarkdownTable.Alignment.LEFT,
            MarkdownTable.Alignment.CENTER,
            MarkdownTable.Alignment.RIGHT),
        t.alignments());
    int[] row = t.cells()[1];
    assertEquals("two", r.text().substring(row[2], row[3]));
    assertEquals("B", Render.covered(r, Render.first(r, InlineStyle.STRONG)));
    assertEquals("two", Render.covered(r, Render.first(r, InlineStyle.CODE)));
    int[] shortRow = t.cells()[2];
    assertEquals(shortRow[4], shortRow[5]);
    assertEquals(0, r.sourceLine(0));
    assertEquals(2, r.sourceLine(1));
    assertEquals(3, r.sourceLine(2));
    assertEquals(MarkdownBlock.Kind.TABLE, r.blocks().get(0).kind());
    assertEquals(3, r.blocks().get(0).lineCount());
  }

  @Test
  void fencedCodeKeepsItsLinesAndLanguage() {
    RenderedMarkdown r = Render.of("Intro\n\n```java\nint x = 1; // c\n```\n");
    CodeBlock code = r.codeBlocks().get(0);
    assertEquals("java", code.language());
    assertEquals(2, code.firstLine());
    assertEquals(1, code.lineCount());
    assertEquals("int x = 1; // c", Render.line(r, 2));
    assertTrue(r.code(2));
    assertFalse(r.code(0));
    assertEquals(3, r.sourceLine(2));
  }

  @Test
  void theLanguageIsTheInfoStringsFirstWordWithoutBraces() {
    assertEquals("bash", Render.of("```bash\nls\n```").codeBlocks().get(0).language());
    assertEquals("C++", Render.of("```C++ x\nint\n```").codeBlocks().get(0).language());
    assertEquals("py", Render.of("```{.py title=x}\npass\n```").codeBlocks().get(0).language());
  }

  @Test
  void indentedCodeAndAnEmptyInfoStringHaveNoLanguage() {
    RenderedMarkdown r = Render.of("```\na\n\tb\n```\n\n    indented\n");
    assertEquals(List.of("a", "\tb", "", "indented"), lines(r));
    assertNull(r.codeBlocks().get(0).language());
    assertNull(r.codeBlocks().get(1).language());
    assertEquals(2, r.codeBlocks().get(0).lineCount());
  }

  @Test
  void anEmptyCodeBlockHasOneLine() {
    RenderedMarkdown r = Render.of("```\n```\n");
    assertEquals(1, r.codeBlocks().get(0).lineCount());
    assertTrue(r.code(0));
  }

  @Test
  void aSupplementaryCharBeforeARunCountsTwoUnits() {
    RenderedMarkdown r = Render.of("😀 **bold**");
    StyleRun strong = Render.first(r, InlineStyle.STRONG);
    assertEquals(3, strong.start());
    assertEquals("bold", Render.covered(r, strong));
  }

  @Test
  void crLfAndCrDocumentsRenderAsLf() {
    String lf = "# H\n\npara\none\n\n```java\nint a;\nint b;\n```\n\n| a |\n|---|\n| 1 |\n";
    RenderedMarkdown expected = Render.of(lf);
    for (String breakChars : new String[] {"\r\n", "\r"}) {
      RenderedMarkdown r = Render.of(lf.replace("\n", breakChars));
      assertEquals(expected.text(), r.text());
      assertEquals(expected.runs(), r.runs());
      for (int i = 0; i < r.lineCount(); i++) {
        assertEquals(expected.sourceLine(i), r.sourceLine(i), "line " + i);
      }
      assertEquals(expected.codeBlocks(), r.codeBlocks());
    }
  }

  @Test
  void anEmptyDocumentHasOneEmptyLine() {
    RenderedMarkdown r = Render.of("");
    assertEquals(1, r.lineCount());
    assertEquals("", r.text());
    assertEquals(0, r.renderedLine(5));
  }

  @Test
  void cancelBeforeTheParseThrows() {
    final CancellationToken token = new CancellationToken();
    token.cancel();
    assertThrows(
        CancellationException.class,
        new Executable() {
          @Override
          public void execute() {
            Render.RENDERER.render("# a", null, token);
          }
        });
  }

  @Test
  void cancelDuringTheWalkStopsAtTheNextBlock() {
    final CancellationToken token = new CancellationToken();
    final Document document = new Document();
    final List<String> walked = new ArrayList<String>();
    document.appendChild(paragraph("first"));
    document.appendChild(
        new CustomBlock() {
          @Override
          public Node getFirstChild() {
            walked.add("canceling");
            token.cancel();
            return null;
          }
        });
    document.appendChild(paragraph("second"));
    assertThrows(
        CancellationException.class,
        new Executable() {
          @Override
          public void execute() {
            new BlockWriter("", new HeadingAnchors(document), null, token).write(document);
          }
        });
    assertEquals(List.of("canceling"), walked);
  }

  @Test
  void aGeneratedInputOverEveryBufferRendersWhole() {
    StringBuilder source = new StringBuilder();
    int sections = 3000;
    for (int i = 0; i < sections; i++) {
      source.append("## Section ").append(i).append("\n\nText **").append(i).append("**.\n\n");
      source.append("- item\n- [x] task\n\n```java\nint v").append(i).append(";\n```\n\n");
      source.append("| a | b |\n|---|---|\n| ").append(i).append(" | x |\n\n");
    }
    assertTrue(source.length() > 1 << 18);
    RenderedMarkdown r = Render.of(source.toString());
    assertEquals(sections, r.headings().size());
    assertEquals(sections, r.codeBlocks().size());
    assertEquals(sections, r.tables().size());
    MarkdownHeading last = r.headings().get(sections - 1);
    assertEquals("Section " + (sections - 1), Render.line(r, last.line()));
    assertEquals(last.line(), r.renderedLine(last.sourceLine()));
    for (int i = 1; i < r.lineCount(); i++) {
      assertTrue(r.sourceLine(i) >= r.sourceLine(i - 1));
    }
  }

  @Test
  void blocksListEveryLeafInOrderWithoutSeparators() {
    RenderedMarkdown r =
        Render.of("# H\n\n> quoted\n\n- item\n\n```\ncode\n```\n\n| a |\n|---|\n| 1 |\n");
    List<MarkdownBlock.Kind> kinds = new ArrayList<MarkdownBlock.Kind>();
    for (MarkdownBlock b : r.blocks()) {
      kinds.add(b.kind());
    }
    assertEquals(
        List.of(
            MarkdownBlock.Kind.HEADING,
            MarkdownBlock.Kind.PARAGRAPH,
            MarkdownBlock.Kind.PARAGRAPH,
            MarkdownBlock.Kind.CODE,
            MarkdownBlock.Kind.TABLE),
        kinds);
    assertEquals(List.of(0, 2, 4, 6, 8), blockLines(r));
    assertEquals(1, r.quoteDepth(2));
    assertEquals(1, r.listDepth(4));
  }

  private static List<Integer> blockLines(RenderedMarkdown r) {
    List<Integer> out = new ArrayList<Integer>();
    for (MarkdownBlock b : r.blocks()) {
      out.add(b.firstLine());
    }
    return out;
  }

  private static Paragraph paragraph(String text) {
    Paragraph p = new Paragraph();
    p.appendChild(new Text(text));
    return p;
  }

  @Test
  void imagesRenderAsAPlaceholderOrAsAltTextAndDestination() {
    RenderedMarkdown local = Render.of("![shot](img/a.png)", Render.folder());
    MarkdownImage image = local.images().get(0);
    assertEquals(String.valueOf(MarkdownImage.PLACEHOLDER), local.text());
    assertTrue(image.drawable());
    assertEquals("shot", image.alt());
    RenderedMarkdown remote = Render.of("![logo](https://example.com/l.png)");
    assertEquals("logo (https://example.com/l.png)", remote.text());
    assertFalse(remote.images().get(0).drawable());
    RenderedMarkdown untitled = Render.of("![a](rel.png)");
    assertEquals("a (rel.png)", untitled.text());
    assertNotNull(untitled.images().get(0).target());
  }
}
