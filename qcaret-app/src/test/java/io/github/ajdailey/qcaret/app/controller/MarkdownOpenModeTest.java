package io.github.ajdailey.qcaret.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.ajdailey.qcaret.app.config.MarkdownOpenMode;
import io.github.ajdailey.qcaret.app.config.Preferences;
import io.github.ajdailey.qcaret.app.session.Session;
import io.github.ajdailey.qcaret.app.view.MarkdownViewMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code markdown.openMode}: the mode of a Markdown file opened with no saved pick. Only a mode the
 * user picked is kept, by the file memory and the session.
 */
class MarkdownOpenModeTest {
  @TempDir Path temp;

  private Harness harness;
  private DocumentController documents;
  private final Map<String, Session.FileState> kept = new HashMap<String, Session.FileState>();

  @BeforeEach
  void create() throws InterruptedException {
    harness = new Harness(temp);
    documents = harness.application.documents();
    documents.setFileMemory(
        new DocumentController.FileMemory() {
          @Override
          public Session.FileState state(Path path) {
            return kept.get(path.toString());
          }

          @Override
          public void remember(Session.FileState state) {
            kept.put(state.path(), state);
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

  private Path write(String name) throws Exception {
    return Files.writeString(temp.resolve(name), "# Mock\n", StandardCharsets.UTF_8);
  }

  private void setOpenMode(MarkdownOpenMode mode) {
    Preferences current = harness.application.settings().current();
    harness
        .application
        .settings()
        .set(current.withOptions(current.options().withMarkdownOpenMode(mode)));
  }

  private void awaitMode(final MarkdownViewMode mode) throws InterruptedException {
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return documents.markdownMode() == mode;
          }
        },
        "mode " + mode + ", was " + documents.markdownMode());
    harness.awaitIdle("mode " + mode);
    assertEquals(mode, documents.markdownMode());
  }

  @Test
  void aFirstOpenTakesTheSetting() throws Exception {
    harness.open(write("a.md"));
    awaitMode(MarkdownViewMode.SPLIT);
    setOpenMode(MarkdownOpenMode.PREVIEW);
    harness.open(write("b.md"));
    awaitMode(MarkdownViewMode.PREVIEW);
    setOpenMode(MarkdownOpenMode.SOURCE);
    harness.open(write("c.md"));
    awaitMode(MarkdownViewMode.SOURCE);
  }

  @Test
  void aDefaultOpenedFileFollowsTheSettingOnItsNextOpen() throws Exception {
    Path file = write("a.md");
    harness.open(file);
    awaitMode(MarkdownViewMode.SPLIT);
    assertEquals("", documents.sessionTabs(false).get(0).markdown());
    closeTab();
    assertEquals("", kept.get(file.toString()).markdown());
    setOpenMode(MarkdownOpenMode.PREVIEW);
    harness.open(file);
    awaitMode(MarkdownViewMode.PREVIEW);
  }

  @Test
  void aPickedModeSurvivesASettingChange() throws Exception {
    Path file = write("a.md");
    harness.open(file);
    awaitMode(MarkdownViewMode.SPLIT);
    documents.setMarkdownMode(MarkdownViewMode.PREVIEW);
    closeTab();
    assertEquals("preview", kept.get(file.toString()).markdown());
    setOpenMode(MarkdownOpenMode.SOURCE);
    harness.open(file);
    awaitMode(MarkdownViewMode.PREVIEW);
  }

  @Test
  void anExplicitSourcePickIsKept() throws Exception {
    Path file = write("a.md");
    harness.open(file);
    awaitMode(MarkdownViewMode.SPLIT);
    documents.setMarkdownMode(MarkdownViewMode.SOURCE);
    closeTab();
    assertEquals("source", kept.get(file.toString()).markdown());
    harness.open(file);
    awaitMode(MarkdownViewMode.SOURCE);
    closeTab();
    assertEquals("source", kept.get(file.toString()).markdown());
  }

  @Test
  void aSessionTabRestoresAPickAndAnEmptyModeTakesTheSetting() throws Exception {
    setOpenMode(MarkdownOpenMode.PREVIEW);
    restore(write("a.md"), "split");
    awaitMode(MarkdownViewMode.SPLIT);
    assertEquals("split", documents.sessionTabs(false).get(0).markdown());
    restore(write("b.md"), "");
    awaitMode(MarkdownViewMode.PREVIEW);
    assertEquals("", documents.sessionTabs(false).get(1).markdown());
  }

  private void closeTab() throws InterruptedException {
    documents.closeActive();
    harness.awaitIdle("close");
  }

  private void restore(Path file, String markdown) throws InterruptedException {
    final int before = documents.count();
    documents.restore(
        new Session.Tab(
            file.toString(),
            "",
            "",
            "",
            "",
            false,
            4,
            false,
            List.of(new Session.View("0,0", 0, "")),
            0,
            false,
            "",
            "",
            List.of(),
            false,
            markdown),
        new Consumer<String>() {
          @Override
          public void accept(String name) {
            throw new AssertionError("not restored: " + name);
          }
        },
        new Runnable() {
          @Override
          public void run() {
            // The count shows the tab.
          }
        });
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return documents.count() == before + 1;
          }
        },
        "restored " + file.getFileName());
    harness.awaitIdle("restore");
  }
}
