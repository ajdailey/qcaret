package io.github.ajdailey.qcaret.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.editor.MarkdownPage;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.theme.Palette;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * File > Export as HTML through the application controller: enabled for a Markdown tab only, the
 * proposed name, a page that opens offline (inline stylesheet, embedded image, remote image as a
 * link, no outside reference), a failed write and the document's own file refused.
 */
class MarkdownExportTest {
  private static final byte[] PNG =
      Base64.getDecoder()
          .decode(
              "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==");

  @TempDir Path temp;

  private Harness harness;
  private CommandStates states;

  @BeforeEach
  void create() throws InterruptedException {
    harness = new Harness(temp);
    states = new CommandStates(harness.application);
    final GrammarLibrary library = harness.application.grammars();
    harness.application.loadGrammars();
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return library.set() != null;
          }
        },
        "grammars read");
  }

  @AfterEach
  void close() throws InterruptedException {
    harness.application.documents().dispose();
    harness.close();
  }

  private Path openMarkdown() throws Exception {
    Files.write(temp.resolve("dot.png"), PNG);
    Path file =
        Files.writeString(
            temp.resolve("notes.md"),
            "# Notes\n\n![dot](dot.png)\n\n![far](https://example.invalid/far.png)\n\n"
                + "[other](other.md)\n",
            StandardCharsets.UTF_8);
    harness.open(file);
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return states.enabled(CommandId.EXPORT_HTML);
          }
        },
        "Markdown grammar");
    return file;
  }

  private void choose(Path target) throws InterruptedException {
    harness.application.execute(CommandId.EXPORT_HTML);
    assertEquals("notes.html", harness.view.saveName);
    harness.view.saveChooser.accept(target);
    harness.awaitIdle("export");
  }

  @Test
  void theExportedPageOpensOffline() throws Exception {
    openMarkdown();
    Path target = temp.resolve("out").resolve("notes.html");
    Files.createDirectories(target.getParent());
    choose(target);
    String page = Files.readString(target, StandardCharsets.UTF_8);
    assertTrue(page.contains(MarkdownPage.POLICY), page);
    assertTrue(page.contains(MarkdownPage.stylesheet(Palette.LIGHT)), page);
    assertTrue(page.contains("<h1 id=\"notes\">Notes</h1>"), page);
    assertTrue(page.contains("src=\"data:image/png;base64,"), page);
    assertTrue(page.contains("<a href=\"https://example.invalid/far.png\" class=\"image-link\">"));
    assertTrue(page.contains("href=\"other.md\""), page);
    assertFalse(page.contains("src=\"http"), page);
    assertFalse(page.contains("file:"), page);
    assertFalse(page.contains("<link"), page);
    assertTrue(
        harness.view.notices.contains(UiText.EXPORT_HTML_DONE.format("notes.md", target)),
        harness.view.notices.toString());
  }

  @Test
  void aFailedWriteShowsTheReason() throws Exception {
    openMarkdown();
    Path target = temp.resolve("missing").resolve("notes.html");
    choose(target);
    assertFalse(Files.exists(target));
    assertEquals(1, harness.view.infos.size());
    assertTrue(
        harness.view.infos.get(0).startsWith(UiText.EXPORT_HTML_TITLE.text() + ": "),
        harness.view.infos.toString());
  }

  @Test
  void theDocumentsOwnFileIsRefused() throws Exception {
    Path file = openMarkdown();
    String before = Files.readString(file, StandardCharsets.UTF_8);
    choose(file);
    assertEquals(before, Files.readString(file, StandardCharsets.UTF_8));
    assertEquals(
        UiText.EXPORT_HTML_TITLE.text() + ": " + UiText.EXPORT_HTML_SOURCE.format("notes.md"),
        harness.view.infos.get(0));
  }

  @Test
  void exportNeedsAMarkdownTab() throws Exception {
    assertFalse(states.enabled(CommandId.EXPORT_HTML));
    harness.open(Files.writeString(temp.resolve("plain.txt"), "text\n"));
    assertFalse(states.enabled(CommandId.EXPORT_HTML));
    harness.application.execute(CommandId.EXPORT_HTML);
    assertNull(harness.view.saveName);
  }
}
