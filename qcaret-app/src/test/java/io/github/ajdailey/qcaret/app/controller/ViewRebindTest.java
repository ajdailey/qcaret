package io.github.ajdailey.qcaret.app.controller;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.view.MarkdownViewMode;
import io.github.ajdailey.qcaret.core.document.Document;
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
 * {@link DocumentController#stageViews} and {@link DocumentController#rebindViews}: the window's
 * controls replaced under its controllers, one tab staged per step.
 */
class ViewRebindTest {
  @TempDir Path temp;

  private Harness harness;
  private DocumentController documents;

  @BeforeEach
  void setUp() {
    harness = new Harness(temp);
    documents = harness.application.documents();
  }

  @AfterEach
  void tearDown() throws Exception {
    harness.close();
  }

  /** Stages every tab in a new view and rebinds the tabs to it. */
  private FakeView rebind() {
    FakeView target = new FakeView(harness.ui);
    StagedTabs staged = documents.stageViews(target);
    int steps = 1;
    while (!staged.step()) {
      steps++;
    }
    assertEquals(Math.max(1, documents.count()), steps);
    documents.rebindViews(staged);
    return target;
  }

  private Document open(String name, String text) throws Exception {
    harness.open(Files.writeString(temp.resolve(name), text, StandardCharsets.UTF_8));
    return documents.activeDocument();
  }

  @Test
  void everyTabGetsNewViewsInOrderAndTheSelectedTabStays() throws Exception {
    Document first = open("first.txt", "one\n");
    Document second = open("second.txt", "two\ntwo\n");
    Document third = open("third.txt", "three\n");
    documents.select(1);
    EditorController editor = documents.activeEditor();
    editor.selections().set(SelectionSet.caret(4));
    editor.type("X");
    FakeView.FakeTab before = harness.view.selected;
    before.topRow = 1;
    List<FakeView.FakeTab> old = new ArrayList<FakeView.FakeTab>(harness.view.tabs);

    FakeView target = rebind();
    harness.ui.runPending();

    assertEquals(List.of(first, second, third), documents.openDocuments());
    assertEquals(3, target.tabs.size());
    for (FakeView.FakeTab tab : old) {
      assertTrue(tab.closed);
      assertFalse(harness.view.tabs.contains(tab));
    }
    assertEquals(1, documents.activeIndex());
    assertSame(second, documents.activeDocument());
    assertSame(editor, documents.activeEditor());
    FakeView.FakeTab after = target.selected;
    assertNotSame(before, after);
    assertSame(target.tabs.get(1), after);
    assertEquals("*second.txt", after.title);
    assertEquals(1, after.topRow);
    assertEquals(5, editor.selections().selections().primary().caret());
    editor.type("Y");
    assertEquals("two\nXYtwo\n", second.text().toString());
  }

  @Test
  void aSplitTabKeepsItsArrangementWeightsAndFocusedPane() throws Exception {
    open("split.txt", "a\nb\nc\n");
    documents.split(true);
    EditorController focused = documents.activeEditor();
    harness.view.selected.weights = new int[] {3, 7};

    FakeView target = rebind();

    FakeView.FakeTab tab = target.selected;
    assertEquals(1, tab.splits.size());
    assertTrue(tab.sideBySide);
    assertArrayEquals(new int[] {3, 7}, tab.weights);
    assertSame(focused, documents.activeEditor());
    assertEquals(DocumentController.Split.VERTICAL, documents.activeSplit());
  }

  @Test
  void aCompositionIsKeptAsTypedText() throws Exception {
    Document document = open("ime.txt", "ab\n");
    EditorController editor = documents.activeEditor();
    editor.selections().set(SelectionSet.caret(1));
    editor.preeditFromView("XY");
    assertFalse(document.isDirty());

    rebind();

    assertNull(document.preeditRange());
    assertEquals("aXYb\n", document.text().toString());
    assertTrue(document.isDirty());
    editor.execute(CommandId.UNDO);
    assertEquals("ab\n", document.text().toString());
  }

  @Test
  void aShownCompletionListShowsInTheNewPane() throws Exception {
    open("words.txt", "mockword mockwind\n");
    final EditorController editor = documents.activeEditor();
    editor.selections().set(SelectionSet.caret(18));
    editor.type("mockw");
    editor.execute(CommandId.COMPLETE_WORD);
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !harness.view.selected.shown.completions.isEmpty();
          }
        },
        "completion list");

    FakeView target = rebind();

    assertEquals(List.of("mockwind", "mockword"), target.selected.shown.completions);
  }

  @Test
  void aMarkdownPreviewShowsItsModeAndContentInTheNewTab() throws Exception {
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
    open("notes.md", "# Title\n\ntext\n");
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return documents.activeMarkdown();
          }
        },
        "Markdown grammar");
    assertTrue(documents.setMarkdownMode(MarkdownViewMode.SPLIT));
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            FakeMarkdownPane pane = harness.view.selected.markdown;
            return pane != null && !pane.shown.isEmpty();
          }
        },
        "rendered preview");

    FakeView target = rebind();

    FakeMarkdownPane pane = target.selected.markdown;
    assertEquals(List.of(MarkdownViewMode.SPLIT), pane.modes);
    assertEquals(1, pane.shown.size());
    assertEquals(MarkdownViewMode.SPLIT, documents.markdownMode());
  }

  @Test
  void stagingLeavesTheShownPanesBoundAndHoldsTheStagedPanesReports() throws Exception {
    Document document = open("held.txt", "one\ntwo\nthree\n");
    EditorController editor = documents.activeEditor();
    FakeView.FakeTab shown = harness.view.selected;
    FakeView target = new FakeView(harness.ui);
    StagedTabs staged = documents.stageViews(target);
    assertTrue(staged.step());
    FakeView.FakeTab building = target.tabs.get(0);
    assertNotSame(editor, building.editor);
    building.editor.viewportChanged(4, 8);
    editor.type("X");

    assertSame(shown, editor.view());
    assertEquals(0, editor.visibleStart());
    assertEquals("Xone\ntwo\nthree\n", document.text().toString());

    documents.rebindViews(staged);

    assertSame(building, editor.view());
    assertSame(building, target.selected);
    assertTrue(shown.closed);
    assertEquals(4, editor.visibleStart());
    assertEquals(1, target.tabs.size());
  }

  @Test
  void tabsOpenedClosedOrMovedDuringStagingAreNeitherLostNorDuplicated() throws Exception {
    Document first = open("first.txt", "1\n");
    Document second = open("second.txt", "2\n");
    open("third.txt", "3\n");
    FakeView target = new FakeView(harness.ui);
    StagedTabs staged = documents.stageViews(target);
    assertFalse(staged.step());
    assertFalse(staged.step());
    FakeView.FakeTab stagedFirst = target.tabs.get(0);
    FakeView.FakeTab stagedSecond = target.tabs.get(1);

    documents.select(2);
    documents.closeActive();
    Document fourth = open("fourth.txt", "4\n");
    documents.select(1);
    harness.view.selected.input.moved(0);
    assertEquals(List.of(second, first, fourth), documents.openDocuments());
    assertFalse(staged.step());
    assertTrue(staged.step());
    documents.rebindViews(staged);

    assertEquals(List.of(second, first, fourth), documents.openDocuments());
    assertEquals(3, target.tabs.size());
    for (int i = 0; i < 3; i++) {
      documents.select(i);
      assertSame(target.tabs.get(i), documents.activeEditor().view());
    }
    assertSame(stagedFirst, target.tabs.get(1));
    assertFalse(stagedFirst.closed);
    assertTrue(stagedSecond.closed);
    assertTrue(harness.view.tabs.isEmpty());
  }

  @Test
  void aTabSplitDuringStagingIsStagedAgainAndATabOpenedAfterTheLastStepIsBound() throws Exception {
    open("split.txt", "a\nb\n");
    FakeView target = new FakeView(harness.ui);
    StagedTabs staged = documents.stageViews(target);
    assertTrue(staged.step());
    FakeView.FakeTab before = target.tabs.get(0);
    documents.split(false);
    Document late = open("late.txt", "late\n");

    documents.rebindViews(staged);

    assertTrue(before.closed);
    assertEquals(2, target.tabs.size());
    assertSame(late, documents.activeDocument());
    assertSame(target.tabs.get(1), documents.activeEditor().view());
    documents.select(0);
    assertEquals(1, target.tabs.get(0).splits.size());
    assertSame(target.tabs.get(0), target.selected);
  }

  @Test
  void aCanceledStagingClosesItsViewsAndLeavesTheTabsBound() throws Exception {
    open("kept.txt", "kept\n");
    EditorController editor = documents.activeEditor();
    FakeView.FakeTab shown = harness.view.selected;
    FakeView target = new FakeView(harness.ui);
    StagedTabs staged = documents.stageViews(target);
    staged.step();

    staged.cancel();

    assertTrue(target.tabs.isEmpty());
    assertSame(shown, editor.view());
    assertFalse(shown.closed);
  }
}
