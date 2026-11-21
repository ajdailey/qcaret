package io.github.ajdailey.qcaret.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.config.PreviewRenderer;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.session.Session;
import io.github.ajdailey.qcaret.app.view.MarkdownViewMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * View > Markdown View and Cycle Markdown View through the application controller: the mode kept in
 * the file memory and the session, the cycle order, the renderer's fallback and the status field.
 */
class MarkdownCommandsTest {
  @TempDir Path temp;

  private Harness harness;
  private CommandStates states;
  private DocumentController documents;
  private final List<Session.FileState> remembered = new ArrayList<Session.FileState>();

  @BeforeEach
  void create() throws InterruptedException {
    harness = new Harness(temp);
    states = new CommandStates(harness.application);
    documents = harness.application.documents();
    documents.setFileMemory(
        new DocumentController.FileMemory() {
          @Override
          public Session.FileState state(Path path) {
            for (Session.FileState state : remembered) {
              if (state.path().equals(path.toString())) {
                return state;
              }
            }
            return null;
          }

          @Override
          public void remember(Session.FileState state) {
            remembered.add(state);
          }
        });
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
    documents.dispose();
    harness.close();
  }

  private Path open(String name) throws Exception {
    Path file = Files.writeString(temp.resolve(name), "# Mock\n\ntext\n", StandardCharsets.UTF_8);
    harness.open(file);
    awaitMode(MarkdownViewMode.SOURCE);
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !states.status().language().isEmpty()
                && !states.status().language().equals(UiText.STATUS_PLAIN_TEXT.text());
          }
        },
        "grammar of " + name);
    return file;
  }

  private void awaitMode(final MarkdownViewMode mode) throws InterruptedException {
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return documents.markdownMode() == mode && harness.view.active.get() == 0;
          }
        },
        "mode " + mode + ", was " + documents.markdownMode());
  }

  @Test
  void theModeFollowsTheFileThroughCloseAndReopen() throws Exception {
    Path file = open("notes.md");
    assertTrue(states.enabled(CommandId.MARKDOWN_SPLIT));
    assertTrue(states.checked(CommandId.MARKDOWN_SOURCE));
    harness.application.execute(CommandId.MARKDOWN_SPLIT);
    awaitMode(MarkdownViewMode.SPLIT);
    assertTrue(states.checked(CommandId.MARKDOWN_SPLIT));
    assertEquals(MarkdownViewMode.SPLIT, harness.view.selected.markdown.mode());
    assertEquals("split", documents.sessionTabs(false).get(0).markdown());
    harness.application.execute(CommandId.CLOSE);
    harness.awaitIdle("close");
    assertEquals("split", remembered.get(0).markdown());
    harness.open(file);
    awaitMode(MarkdownViewMode.SPLIT);
    open("other.md");
    assertEquals(MarkdownViewMode.SOURCE, documents.markdownMode());
  }

  @Test
  void aSessionTabRestoresItsMode() throws Exception {
    Path file = open("notes.md");
    harness.application.execute(CommandId.MARKDOWN_PREVIEW);
    awaitMode(MarkdownViewMode.PREVIEW);
    Session.Tab tab = documents.sessionTabs(false).get(0);
    harness.application.execute(CommandId.CLOSE);
    harness.awaitIdle("close");
    documents.restore(
        tab,
        new Consumer<String>() {
          @Override
          public void accept(String failed) {
            throw new AssertionError(failed);
          }
        },
        new Runnable() {
          @Override
          public void run() {
            // The mode check waits.
          }
        });
    awaitMode(MarkdownViewMode.PREVIEW);
    assertEquals(file.toString(), documents.sessionTabs(false).get(0).path());
  }

  @Test
  void aTextTabHasNoModesAndNoPreviewField() throws Exception {
    Path file = Files.writeString(temp.resolve("notes.txt"), "# Mock\n", StandardCharsets.UTF_8);
    harness.open(file);
    harness.awaitIdle("open");
    assertFalse(states.enabled(CommandId.MARKDOWN_SPLIT));
    harness.application.execute(CommandId.MARKDOWN_PREVIEW);
    assertEquals(MarkdownViewMode.SOURCE, documents.markdownMode());
    assertEquals("", states.status().preview());
  }

  @Test
  void cycleRunsSourceSplitPreviewAndBackToSource() throws Exception {
    open("notes.md");
    assertTrue(states.enabled(CommandId.MARKDOWN_CYCLE));
    harness.application.execute(CommandId.MARKDOWN_CYCLE);
    awaitMode(MarkdownViewMode.SPLIT);
    assertTrue(states.checked(CommandId.MARKDOWN_SPLIT));
    harness.application.execute(CommandId.MARKDOWN_CYCLE);
    awaitMode(MarkdownViewMode.PREVIEW);
    assertEquals(MarkdownViewMode.PREVIEW, harness.view.selected.markdown.mode());
    harness.application.execute(CommandId.MARKDOWN_CYCLE);
    awaitMode(MarkdownViewMode.SOURCE);
    assertTrue(states.checked(CommandId.MARKDOWN_SOURCE));
  }

  @Test
  void syncScrollingTogglesTheSavedSettingWithoutADocument() {
    assertTrue(states.enabled(CommandId.MARKDOWN_SYNC_SCROLL));
    assertTrue(states.checked(CommandId.MARKDOWN_SYNC_SCROLL));
    harness.application.execute(CommandId.MARKDOWN_SYNC_SCROLL);
    assertFalse(harness.application.preferences().options().markdownSyncScroll());
    assertFalse(states.checked(CommandId.MARKDOWN_SYNC_SCROLL));
    harness.application.execute(CommandId.MARKDOWN_SYNC_SCROLL);
    assertTrue(harness.application.preferences().options().markdownSyncScroll());
    assertTrue(states.checked(CommandId.MARKDOWN_SYNC_SCROLL));
  }

  @Test
  void cycleDoesNothingOnATextTab() throws Exception {
    Path file = Files.writeString(temp.resolve("notes.txt"), "# Mock\n", StandardCharsets.UTF_8);
    harness.open(file);
    harness.awaitIdle("open");
    assertFalse(states.enabled(CommandId.MARKDOWN_CYCLE));
    harness.application.execute(CommandId.MARKDOWN_CYCLE);
    harness.awaitIdle("cycle");
    assertEquals(MarkdownViewMode.SOURCE, documents.markdownMode());
    assertEquals("", states.status().preview());
  }

  @Test
  void aSavedBrowserChoiceShowsBasicWithANoticeWhileBrowserCannotRun() throws Exception {
    harness.view.browserUnavailable = "mock reason";
    Settings settings = harness.application.settings();
    settings.set(
        settings
            .current()
            .withOptions(
                settings.current().options().withPreviewRenderer(PreviewRenderer.BROWSER)));
    open("notes.md");
    assertFalse(states.enabled(CommandId.PREVIEW_BROWSER));
    assertEquals("mock reason", states.unavailable(CommandId.PREVIEW_BROWSER));
    assertTrue(states.checked(CommandId.PREVIEW_BASIC));
    assertEquals("", states.status().preview());
    harness.application.execute(CommandId.MARKDOWN_SPLIT);
    awaitMode(MarkdownViewMode.SPLIT);
    assertEquals(
        UiText.STATUS_PREVIEW.format(UiText.PREVIEW_RENDERER_BASIC.text()),
        states.status().preview());
    assertEquals(1, count(UiText.MARKDOWN_BROWSER_FALLBACK.text()));
    harness.application.execute(CommandId.MARKDOWN_PREVIEW);
    awaitMode(MarkdownViewMode.PREVIEW);
    assertEquals(1, count(UiText.MARKDOWN_BROWSER_FALLBACK.text()));
    harness.application.execute(CommandId.PREVIEW_BROWSER);
    assertEquals(PreviewRenderer.BROWSER, settings.current().options().previewRenderer());
    harness.application.execute(CommandId.PREVIEW_BASIC);
    assertEquals(PreviewRenderer.BASIC, settings.current().options().previewRenderer());
  }

  @Test
  void browserIsChosenWhereItCanRun() throws Exception {
    open("notes.md");
    harness.application.execute(CommandId.PREVIEW_BROWSER);
    assertTrue(states.checked(CommandId.PREVIEW_BROWSER));
    harness.application.execute(CommandId.MARKDOWN_SPLIT);
    awaitMode(MarkdownViewMode.SPLIT);
    assertEquals(
        UiText.STATUS_PREVIEW.format(UiText.PREVIEW_RENDERER_BROWSER.text()),
        states.status().preview());
    assertEquals(0, count(UiText.MARKDOWN_BROWSER_FALLBACK.text()));
  }

  private int count(String notice) {
    int n = 0;
    for (String shown : harness.view.notices) {
      n += shown.equals(notice) ? 1 : 0;
    }
    return n;
  }
}
