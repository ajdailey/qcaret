package io.github.ajdailey.qcaret.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.view.FindListener;
import io.github.ajdailey.qcaret.app.view.FindState;
import io.github.ajdailey.qcaret.app.view.MarkdownViewMode;
import io.github.ajdailey.qcaret.app.view.SearchScope;
import io.github.ajdailey.qcaret.core.document.Selection;
import io.github.ajdailey.qcaret.core.markdown.RenderedMarkdown;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Find, Replace and HyperSearch on a Markdown tab in Split and in Preview: each acts on the source
 * document, the commands stay enabled, the mode stays, and the preview renders the replaced text.
 * In Preview each match scrolls the preview to its block and selects its rendered text.
 */
class MarkdownFindTest {
  /** The source; "needle" sits inside Markdown markup that the render drops. */
  private static final String TEXT = "# Mock needle\n\nA **needle** here.\n";

  @TempDir Path temp;

  private Harness harness;
  private CommandStates states;
  private DocumentController documents;
  private FindState last;

  @BeforeEach
  void create() throws Exception {
    harness = new Harness(temp);
    states = new CommandStates(harness.application);
    documents = harness.application.documents();
    harness
        .application
        .find()
        .addListener(
            new FindListener() {
              @Override
              public void findStateChanged(FindState state) {
                last = state;
              }

              @Override
              public void focusRequested() {
                // The fake view has no field.
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
    harness.open(Files.writeString(temp.resolve("guide.md"), TEXT, StandardCharsets.UTF_8));
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return documents.activeMarkdown();
          }
        },
        "markdown grammar");
  }

  @AfterEach
  void close() throws InterruptedException {
    documents.dispose();
    harness.close();
  }

  private void mode(final MarkdownViewMode mode) throws InterruptedException {
    assertTrue(documents.setMarkdownMode(mode));
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return renderedText().contains("needle") && harness.view.active.get() == 0;
          }
        },
        "render in " + mode);
  }

  private String renderedText() {
    MarkdownPreview p = (MarkdownPreview) documents.markdownPreview();
    return p == null || p.shown() == null ? "" : p.shown().rendered().text();
  }

  private Selection selected() {
    return documents.activeEditor().selections().selections().primary();
  }

  private void findSelects(final int start, final int end) throws InterruptedException {
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            Selection s = selected();
            return s.start() == start && s.end() == end && !last.running();
          }
        },
        "selection " + start + ".." + end + ", was " + selected());
  }

  private void findAndReplace(MarkdownViewMode mode) throws Exception {
    mode(mode);
    assertTrue(states.enabled(CommandId.FIND));
    assertTrue(states.enabled(CommandId.REPLACE));
    harness.application.execute(CommandId.REPLACE);
    assertTrue(last.open());
    FindController find = harness.application.find();
    find.setText("needle");
    findSelects(7, 13);
    revealed(mode, 1, 0);
    find.findNext();
    findSelects(19, 25);
    revealed(mode, 2, 2);
    find.setReplacement("pin");
    find.replace();
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return renderedText().contains("A pin here.") && harness.view.active.get() == 0;
          }
        },
        "the preview renders the replaced source, shows " + renderedText());
    assertEquals(
        "# Mock needle\n\nA **pin** here.\n", documents.activeDocument().text().toString());
    assertEquals(mode, documents.markdownMode());
    findSelects(7, 13);
    revealed(mode, 3, 0);
    find.close();
  }

  private FakeMarkdownPane pane() {
    return harness.view.tabs.get(0).markdown;
  }

  /**
   * In Preview, waits for a count of rendered selections, then asserts the last scroll went to a
   * source line's block and the last selection is that block's "needle". Elsewhere asserts no
   * selection.
   */
  private void revealed(MarkdownViewMode mode, final int count, int sourceLine)
      throws InterruptedException {
    final FakeMarkdownPane pane = pane();
    if (mode != MarkdownViewMode.PREVIEW) {
      assertEquals(0, pane.selects.size());
      return;
    }
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return pane.selects.size() == count;
          }
        },
        count + " selections, were " + pane.selects.size());
    RenderedMarkdown r = ((MarkdownPreview) documents.markdownPreview()).shown().rendered();
    int line = r.renderedLine(sourceLine);
    assertEquals(line, pane.scrolls.get(pane.scrolls.size() - 1));
    int[] s = pane.selects.get(count - 1);
    assertEquals("needle", r.text().substring(s[0], s[1]));
    assertEquals(line, lineOf(r, s[0]));
  }

  private static int lineOf(RenderedMarkdown r, int offset) {
    int line = 0;
    while (line + 1 < r.lineCount() && r.lineStart(line + 1) <= offset) {
      line++;
    }
    return line;
  }

  @Test
  void findAndReplaceActOnTheSourceInSplit() throws Exception {
    findAndReplace(MarkdownViewMode.SPLIT);
  }

  @Test
  void findAndReplaceActOnTheSourceInPreview() throws Exception {
    findAndReplace(MarkdownViewMode.PREVIEW);
  }

  @Test
  void findInSourceRevealsNothing() throws Exception {
    mode(MarkdownViewMode.PREVIEW);
    FakeMarkdownPane pane = pane();
    int scrolls = pane.scrolls.size();
    assertTrue(documents.setMarkdownMode(MarkdownViewMode.SOURCE));
    harness.application.execute(CommandId.FIND);
    harness.application.find().setText("needle");
    findSelects(7, 13);
    harness.application.find().findNext();
    findSelects(19, 25);
    assertEquals(scrolls, pane.scrolls.size());
    assertEquals(0, pane.selects.size());
  }

  @Test
  void hyperSearchReadsAndOpensTheSourceInPreview() throws Exception {
    mode(MarkdownViewMode.PREVIEW);
    assertTrue(states.enabled(CommandId.HYPERSEARCH));
    final SearchController search = harness.application.search();
    search.run(SearchControllerTest.form("needle", SearchScope.CURRENT_DOCUMENT, ""));
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !search.running();
          }
        },
        "search ends");
    harness.ui.runPending();
    assertEquals(2, search.matchCount(0, 0));
    assertEquals("3: A **needle** here.", search.match(0, 0, 1).text());
    search.open(0, 0, 1);
    assertEquals(Selection.of(19, 25), selected());
    assertEquals(MarkdownViewMode.PREVIEW, documents.markdownMode());
    revealed(MarkdownViewMode.PREVIEW, 1, 2);
  }
}
