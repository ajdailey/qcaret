package io.github.ajdailey.qcaret.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.view.MarkdownPreviewContent;
import io.github.ajdailey.qcaret.app.view.MarkdownViewMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A preview link to a web address asks before it opens, through {@code MainView.askOpenUrl}: Open
 * launches the address, Cancel launches nothing. A link to a local file opens it without asking.
 */
class PreviewLinkAskTest {
  private static final String URL = "https://example.invalid/mock";

  @TempDir Path temp;

  private Harness harness;

  @BeforeEach
  void create() throws Exception {
    harness = new Harness(temp);
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
    Files.writeString(temp.resolve("other.md"), "# Other\n", StandardCharsets.UTF_8);
    Path file =
        Files.writeString(
            temp.resolve("notes.md"),
            "# Mock\n\n[web](" + URL + ") and [local](other.md)\n",
            StandardCharsets.UTF_8);
    harness.open(file);
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return harness.application.documents().activeMarkdown();
          }
        },
        "markdown");
    harness.application.execute(CommandId.MARKDOWN_SPLIT);
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            FakeMarkdownPane pane = harness.view.selected.markdown;
            return harness.application.documents().markdownMode() == MarkdownViewMode.SPLIT
                && pane != null
                && !pane.shown.isEmpty();
          }
        },
        "rendered");
  }

  @AfterEach
  void close() throws InterruptedException {
    harness.application.documents().dispose();
    harness.close();
  }

  private void click(String text) {
    FakeMarkdownPane pane = harness.view.selected.markdown;
    MarkdownPreviewContent content = pane.shown.get(pane.shown.size() - 1);
    pane.input.clicked(content.rendered().text().indexOf(text) + 1);
  }

  @Test
  void openLaunchesTheAddress() {
    click("web");
    assertEquals(List.of(URL), harness.view.urlsAsked);
    assertEquals(List.of(URL), harness.view.launched);
  }

  @Test
  void cancelLaunchesNothing() {
    harness.view.openUrls = false;
    click("web");
    assertEquals(List.of(URL), harness.view.urlsAsked);
    assertTrue(harness.view.launched.isEmpty());
  }

  @Test
  void aLocalLinkOpensWithoutAsking() throws InterruptedException {
    final int before = harness.application.documents().count();
    click("local");
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return harness.application.documents().count() == before + 1;
          }
        },
        "other.md opened");
    assertTrue(harness.view.urlsAsked.isEmpty());
  }
}
