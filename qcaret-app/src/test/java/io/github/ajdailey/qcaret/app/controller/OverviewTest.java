package io.github.ajdailey.qcaret.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.editor.OverviewMarks;
import io.github.ajdailey.qcaret.app.view.SearchScope;
import io.github.ajdailey.qcaret.core.document.SelectionSet;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The overview strip's marks against a fake view: find-bar matches while the bar is open,
 * HyperSearch matches of the set shown, bookmarks, recomputed after an edit at the new revision,
 * and nothing computed while the strip is hidden.
 */
class OverviewTest {
  @TempDir java.nio.file.Path temp;

  private Harness harness;
  private EditorController editor;
  private FakeView.FakeTab tab;

  @BeforeEach
  void create() throws Exception {
    harness = new Harness(temp);
    StringBuilder text = new StringBuilder();
    for (int i = 0; i < 100; i++) {
      text.append(i == 10 ? "find" : i == 50 ? "hyper" : "line").append('\n');
    }
    harness.open(
        Files.writeString(temp.resolve("strip.txt"), text.toString(), StandardCharsets.UTF_8));
    editor = harness.application.documents().activeEditor();
    tab = harness.view.selected;
    editor.overviewResized(101);
  }

  @AfterEach
  void close() throws InterruptedException {
    harness.application.documents().dispose();
    harness.close();
  }

  /** Rows holding a kind of mark. */
  private List<Integer> rows(int kind) {
    List<Integer> out = new ArrayList<Integer>();
    byte[] rows = tab.shown.overview.rows();
    for (int y = 0; y < rows.length; y++) {
      if ((rows[y] & kind) != 0) {
        out.add(y);
      }
    }
    return out;
  }

  private void awaitRows(final int kind, final List<Integer> expected) throws InterruptedException {
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return rows(kind).equals(expected)
                && tab.shown.overview.revision() == editor.document().revision();
          }
        },
        "rows of kind " + kind + " at " + expected + ", shown " + rows(kind));
  }

  @Test
  void findHyperSearchAndBookmarkMarksFollowTheirSources() throws Exception {
    assertTrue(tab.shown.overviewShown);
    harness.application.find().open(false);
    harness.application.find().setText("find");
    awaitRows(OverviewMarks.FIND, List.of(10));
    harness
        .application
        .search()
        .run(SearchControllerTest.form("hyper", SearchScope.CURRENT_DOCUMENT, ""));
    awaitRows(OverviewMarks.SEARCH, List.of(50));
    editor.selections().set(SelectionSet.caret(editor.document().text().offsetOfLine(70)));
    harness.application.execute(CommandId.TOGGLE_BOOKMARK);
    awaitRows(OverviewMarks.BOOKMARK, List.of(70));
    editor.selections().set(SelectionSet.caret(0));
    editor.type("one\n".repeat(10));
    awaitRows(OverviewMarks.SEARCH, List.of(54));
    assertEquals(110 + 1, tab.shown.overview.lineCount());
    harness.application.find().close();
    awaitRows(OverviewMarks.FIND, List.of());
  }

  @Test
  void aHiddenStripComputesNothing() throws Exception {
    harness.application.execute(CommandId.OVERVIEW_STRIP);
    assertFalse(tab.shown.overviewShown);
    OverviewMarks before = tab.shown.overview;
    harness.application.find().open(false);
    harness.application.find().setText("find");
    harness.awaitIdle("find");
    harness.ui.runPending();
    assertTrue(before == tab.shown.overview);
    harness.application.execute(CommandId.OVERVIEW_STRIP);
    awaitRows(OverviewMarks.FIND, List.of(10));
  }
}
