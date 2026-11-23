package io.github.ajdailey.qcaret.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.config.MarkdownOpenMode;
import io.github.ajdailey.qcaret.app.config.Preferences;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.view.MarkdownViewMode;
import io.github.ajdailey.qcaret.app.view.OutlineEntry;
import io.github.ajdailey.qcaret.core.document.SelectionSet;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Outline dock's model through the application controller: the toggle, refresh after an edit,
 * the stale drop, tab and grammar changes, the caret's entry, go to with the preview in Preview,
 * and the size limit.
 */
class MarkdownOutlineTest {
  private static final String TEXT = "# Guide\n\nIntro.\n\n## Setup\n\nSteps.\n\n## Usage\n";

  @TempDir Path temp;

  private Harness harness;
  private DocumentController documents;
  private MarkdownOutline outline;

  @BeforeEach
  void create() throws InterruptedException {
    harness = new Harness(temp);
    documents = harness.application.documents();
    outline = harness.application.outline();
    // These cases start each file in Source and switch from there.
    Preferences current = harness.application.settings().current();
    harness
        .application
        .settings()
        .set(current.withOptions(current.options().withMarkdownOpenMode(MarkdownOpenMode.SOURCE)));
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

  private void open(String name, String text) throws Exception {
    harness.open(Files.writeString(temp.resolve(name), text, StandardCharsets.UTF_8));
    final boolean markdown = name.endsWith(".md");
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return documents.activeMarkdown() == markdown;
          }
        },
        "grammar of " + name);
    harness.awaitIdle(name);
  }

  private static List<String> names(List<OutlineEntry> entries) {
    List<String> out = new ArrayList<String>();
    for (OutlineEntry e : entries) {
      out.add(e.text());
    }
    return out;
  }

  private void awaitNames(final String what, final String... expected) throws InterruptedException {
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return names(outline.entries()).equals(List.of(expected))
                && outline.runningRender() == null;
          }
        },
        what + ", was " + names(outline.entries()));
  }

  private void caretAt(int line) {
    EditorController editor = documents.activeEditor();
    editor.selections().set(SelectionSet.caret(editor.document().text().offsetOfLine(line)));
  }

  @Test
  void theToggleShowsTheTreeAndAnEditRefreshesIt() throws Exception {
    open("guide.md", TEXT);
    CommandStates states = new CommandStates(harness.application);
    assertTrue(states.enabled(CommandId.MARKDOWN_OUTLINE));
    assertFalse(states.checked(CommandId.MARKDOWN_OUTLINE));
    harness.application.execute(CommandId.MARKDOWN_OUTLINE);
    assertTrue(harness.view.outlineVisible);
    assertTrue(states.checked(CommandId.MARKDOWN_OUTLINE));
    awaitNames("first render", "Guide", "Setup", "Usage");
    assertEquals(List.of(-1, 0, 0), parents());
    assertEquals("guide.md: 3 headings", outline.summary());
    assertEquals(MarkdownViewMode.SOURCE, documents.markdownMode());

    caretAt(documents.activeDocument().text().lineCount() - 1);
    documents.activeEditor().type("\n### Detail\n");
    awaitNames("after an edit", "Guide", "Setup", "Usage", "Detail");
    assertEquals(2, outline.entries().get(3).parent());

    harness.application.execute(CommandId.MARKDOWN_OUTLINE);
    assertFalse(harness.view.outlineVisible);
    assertEquals(List.of(), outline.entries());
  }

  private List<Integer> parents() {
    List<Integer> out = new ArrayList<Integer>();
    for (OutlineEntry e : outline.entries()) {
      out.add(e.parent());
    }
    return out;
  }

  @Test
  void aRenderOvertakenByAnEditIsDropped() throws Exception {
    open("guide.md", "# One\n");
    harness.application.execute(CommandId.MARKDOWN_OUTLINE);
    // The first render read "# One" alone; the edit overtakes it before its result runs.
    assertTrue(outline.runningRender() != null);
    caretAt(0);
    documents.activeEditor().type("# Zero\n");
    awaitNames("render of the edited text", "Zero", "One");
    for (List<OutlineEntry> shown : harness.view.outlineShown) {
      assertFalse(names(shown).equals(List.of("One")), "stale entries shown");
    }
  }

  @Test
  void theOutlineFollowsTabsAndTheGrammar() throws Exception {
    open("guide.md", TEXT);
    harness.application.execute(CommandId.MARKDOWN_OUTLINE);
    awaitNames("Markdown tab", "Guide", "Setup", "Usage");
    open("notes.txt", "# not markdown\n");
    awaitNames("plain tab");
    assertEquals(UiText.OUTLINE_NOT_MARKDOWN.text(), outline.summary());
    harness.view.tabs.get(0).select();
    awaitNames("Markdown tab again", "Guide", "Setup", "Usage");
    documents.setSyntax("plain");
    awaitNames("plain text chosen");
    documents.setSyntax("markdown");
    awaitNames("Markdown chosen", "Guide", "Setup", "Usage");
  }

  @Test
  void theSelectionFollowsTheCaretAndGoToMovesIt() throws Exception {
    open("guide.md", TEXT);
    harness.application.execute(CommandId.MARKDOWN_OUTLINE);
    awaitNames("render", "Guide", "Setup", "Usage");
    caretAt(5);
    assertEquals(1, outline.selected());
    assertEquals(Integer.valueOf(1), last(harness.view.outlineSelections));
    caretAt(0);
    assertEquals(0, outline.selected());

    outline.goTo(2, false);
    EditorController editor = documents.activeEditor();
    assertEquals(
        8, editor.document().text().lineAt(editor.selections().selections().primary().caret()));
    assertEquals(2, outline.selected());
    FakeView.FakeTab tab = harness.view.tabs.get(0);
    assertEquals(editor.projection().rowOfLine(8), tab.scrolledTo);
  }

  @Test
  void goToScrollsThePreviewInPreview() throws Exception {
    open("guide.md", TEXT);
    harness.application.execute(CommandId.MARKDOWN_OUTLINE);
    awaitNames("render", "Guide", "Setup", "Usage");
    harness.application.execute(CommandId.MARKDOWN_PREVIEW);
    final FakeMarkdownPane pane = harness.view.tabs.get(0).markdown;
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !pane.shown.isEmpty() && harness.view.active.get() == 0;
          }
        },
        "preview rendered");
    outline.goTo(1, false);
    int rendered = last(pane.shown).rendered().renderedLine(4);
    assertEquals(Integer.valueOf(rendered), last(pane.scrolls));
    assertTrue(rendered > 0);
  }

  @Test
  void aDocumentOverTheLimitListsNothing() throws Exception {
    open("guide.md", TEXT);
    MarkdownOutline small =
        new MarkdownOutline(documents, harness.workers, harness.ui, harness.view, 10);
    small.toggle();
    harness.ui.runPending();
    assertEquals(List.of(), small.entries());
    assertEquals(UiText.OUTLINE_TOO_LARGE.format("guide.md", 10), small.summary());
    small.dispose();
  }

  private static <T> T last(List<T> list) {
    return list.get(list.size() - 1);
  }
}
