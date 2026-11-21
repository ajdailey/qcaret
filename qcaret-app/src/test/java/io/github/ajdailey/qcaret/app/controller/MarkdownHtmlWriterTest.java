package io.github.ajdailey.qcaret.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.view.MarkdownHtml;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.markdown.MarkdownRenderer;
import io.github.ajdailey.qcaret.core.syntax.GrammarReader;
import io.github.ajdailey.qcaret.core.syntax.GrammarSet;
import io.github.ajdailey.qcaret.core.syntax.SyntaxEngine;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Browser preview's and the export's HTML: each element and extension, GitLab's two blocks,
 * heading anchors and line ids, colored code, embedded and remote images, link targets and the
 * cancel check.
 */
class MarkdownHtmlWriterTest {
  private static final GrammarSet GRAMMARS =
      GrammarSet.of(
          GrammarSet.readBuiltIns(new GrammarReader()), Map.of(), new ArrayList<String>());

  /** A 1 by 1 PNG. */
  private static final byte[] PNG =
      Base64.getDecoder()
          .decode(
              "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==");

  @TempDir Path temp;

  private MarkdownHtml preview(String text, Path folder) {
    return MarkdownHtmlWriter.preview(
        new MarkdownRenderer().parse(text, new CancellationToken()),
        folder,
        GRAMMARS,
        SyntaxEngine.DEFAULT_MAX_LINE_CHARS,
        new CancellationToken());
  }

  private String body(String text) {
    return preview(text, temp).body();
  }

  private String export(String text) {
    return MarkdownHtmlWriter.export(
            new MarkdownRenderer().parse(text, new CancellationToken()),
            temp,
            GRAMMARS,
            SyntaxEngine.DEFAULT_MAX_LINE_CHARS,
            new CancellationToken())
        .body();
  }

  private static void assertHas(String html, String part) {
    assertTrue(html.contains(part), part + " in " + html);
  }

  @Test
  void commonMarkElementsAndGfmExtensions() {
    String html =
        body(
            "*em* **strong** ~~gone~~ `code`\n\n"
                + "> quote\n\n"
                + "---\n\n"
                + "| A | B |\n|---|:-:|\n| 1 | 2 |\n\n"
                + "- [x] done\n- [ ] open\n\n"
                + "1. one\n2. two\n\n"
                + "See https://example.invalid/page\n");
    assertHas(html, "<em>em</em>");
    assertHas(html, "<strong>strong</strong>");
    assertHas(html, "<del>gone</del>");
    assertHas(html, "<code>code</code>");
    assertHas(html, "<blockquote");
    assertHas(html, "<hr");
    assertHas(html, "<th>A</th>");
    assertHas(html, "<th align=\"center\">B</th>");
    assertHas(html, "<input type=\"checkbox\" disabled=\"\" checked=\"\">");
    assertHas(html, "<ol");
    assertHas(html, "href=\"https://example.invalid/page\">https://example.invalid/page</a>");
  }

  @Test
  void footnotesAlertsAndFrontMatter() {
    String html =
        body("---\ntitle: Mock\n---\n\nText[^1].\n\n> [!WARNING]\n> Careful.\n\n[^1]: The note.\n");
    assertHas(html, "<pre class=\"front-matter\"");
    assertHas(html, "title");
    assertHas(html, "class=\"footnote-ref\"");
    assertHas(html, "The note.");
    assertHas(html, "markdown-alert-warning");
  }

  @Test
  void headingsCarryGitLabAnchorsAndBlocksCarryLineIds() {
    MarkdownHtml html = preview("# A  b\n\ntext\n\n## A  b\n\n- item\n", temp);
    assertHas(html.body(), "<h1 id=\"a-b\">");
    assertHas(html.body(), "<h2 id=\"a-b-1\">");
    assertHas(html.body(), "<p id=\"qcaret-line-2\">");
    assertHas(html.body(), "<ul id=\"qcaret-line-6\">");
    assertEquals(List.of("a-b", "qcaret-line-2", "a-b-1", "qcaret-line-6"), html.ids());
    assertEquals("a-b", html.idAt(1));
    assertEquals("qcaret-line-2", html.idAt(3));
    assertEquals("qcaret-line-6", html.idAt(99));
    assertNull(preview("\n\ntext\n", temp).idAt(1));
  }

  @Test
  void gitLabTocAndMultilineQuote() {
    String html = body("[[_TOC_]]\n\n# One\n\n## Two\n\n# Three\n\n>>>\nQuoted\n\ntwice\n>>>\n");
    assertHas(
        html,
        "<ul class=\"section-nav\" id=\"qcaret-line-0\"><li><a href=\"#one\">One</a><ul><li><a"
            + " href=\"#two\">Two</a></li></ul></li><li><a href=\"#three\">Three</a></li></ul>");
    assertHas(html, "<blockquote id=\"qcaret-line-8\">");
    assertHas(html, "<p id=\"qcaret-line-9\">Quoted</p>");
    assertHas(html, "<p id=\"qcaret-line-11\">twice</p>");
  }

  @Test
  void fencedCodeIsColoredByQCaretGrammars() {
    String html = body("```java\nint x = 1; // note\n```\n");
    assertHas(html, "<code class=\"language-java\">");
    assertHas(html, "<span class=\"tk-type\">int</span>");
    assertHas(html, "<span class=\"tk-comment\">// note</span>");
    assertHas(body("```\n<b>&\n```\n"), "&lt;b&gt;&amp;");
  }

  @Test
  void rawHtmlStaysAsWritten() {
    String html = body("<div class=\"mock\"><kbd>K</kbd></div>\n\nText <sub>2</sub>\n");
    assertHas(html, "<div class=\"mock\"><kbd>K</kbd></div>");
    assertHas(html, "<sub>2</sub>");
  }

  @Test
  void localImagesEmbedAndRemoteImagesBecomeLinks() throws Exception {
    Files.write(temp.resolve("dot.png"), PNG);
    String html =
        body(
            "![dot](dot.png){width=20 height=10}\n\n"
                + "![far](https://example.invalid/a.png)\n\n"
                + "![gone](missing.png)\n");
    assertHas(html, "src=\"data:image/png;base64," + Base64.getEncoder().encodeToString(PNG));
    assertHas(html, "width=\"20\"");
    assertHas(html, "<a href=\"https://example.invalid/a.png\" class=\"image-link\">far</a>");
    assertHas(html, "<span class=\"image-missing\">gone</span>");
    assertFalse(html.contains("src=\"http"));
    assertFalse(html.contains("dot.png\""));
  }

  @Test
  void imagesOverTheLimitsStayUnembedded() {
    assertNull(MarkdownHtmlWriter.mediaType(temp.resolve("notes.txt")));
    assertEquals("image/svg+xml", MarkdownHtmlWriter.mediaType(temp.resolve("a.SVG")));
    assertHas(body("![x](dot.txt)\n"), "<span class=\"image-missing\">x</span>");
  }

  @Test
  void previewLinksToFilesCarryThePreviewSchemeAndExportKeepsThemAsWritten() {
    Path absolute = temp.resolve("abs.txt").toAbsolutePath();
    String link =
        "[doc](docs/a.md) [top](#one) [web](https://example.invalid) [bad](javascript:x)"
            + " [share](smb://host.invalid/s) [abs]("
            + absolute.toUri()
            + ")";
    String preview = body(link + "\n");
    String docUrl = temp.resolve("docs/a.md").toUri().toString();
    assertHas(preview, "href=\"qcaret-file:" + docUrl.substring("file:".length()) + "\"");
    assertHas(
        preview, "href=\"qcaret-file:" + absolute.toUri().toString().substring("file:".length()));
    assertHas(preview, "href=\"#one\"");
    assertHas(preview, "href=\"https://example.invalid\"");
    assertFalse(preview.contains("javascript:"));
    assertFalse(preview.contains("href=\"\""));
    assertHas(preview, "<a rel=\"nofollow\">bad</a>");
    assertHas(preview, "<a rel=\"nofollow\">share</a>");
    String exported = export(link + "\n");
    assertHas(exported, "href=\"docs/a.md\"");
    assertFalse(exported.contains("qcaret-file:"));
    assertFalse(exported.contains("javascript:"));
    assertFalse(exported.contains("href=\"\""));
  }

  @Test
  void aCanceledTokenStopsTheWrite() {
    final CancellationToken token = new CancellationToken();
    token.cancel();
    assertThrows(
        CancellationException.class,
        new Executable() {
          @Override
          public void execute() {
            MarkdownHtmlWriter.preview(
                new MarkdownRenderer().parse("# One\n", new CancellationToken()),
                null,
                null,
                100,
                token);
          }
        });
  }
}
