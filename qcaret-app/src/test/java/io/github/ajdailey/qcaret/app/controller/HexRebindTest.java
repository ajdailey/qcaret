package io.github.ajdailey.qcaret.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.view.HexKey;
import io.github.ajdailey.qcaret.app.view.HexMode;
import io.github.ajdailey.qcaret.app.view.WorkspaceNode;
import io.github.ajdailey.qcaret.app.view.WorkspaceSelectionInput;
import io.github.ajdailey.qcaret.core.document.Document;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Hex views that outlive their window's controls: a tab moved to another window keeps its hex mode
 * ({@link HexPanes#adopt}); a window replacement ({@link DocumentController#rebindViews}) creates
 * the panes and Hex Viewer windows again, and the replaced ones' disposal leaves the new ones
 * attached; the Workspace Browser row menu opens files in Hex Viewer windows.
 */
class HexRebindTest {
  @TempDir Path temp;

  private static void awaitPane(Windows windows, final FakeHexView pane, final long size)
      throws InterruptedException {
    windows.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return pane.rows != null && pane.rows.size() == size && !pane.dimmed;
          }
        },
        "the pane's bytes");
  }

  @Test
  void aTabMovedToAnotherWindowKeepsItsHexMode() throws Exception {
    Windows windows = new Windows(temp, false);
    try {
      ApplicationController first = windows.first();
      windows.open(first, Files.writeString(temp.resolve("a.txt"), "abc\n"));
      Document document = first.documents().activeDocument();
      first.execute(CommandId.HEX_HEX);
      FakeView source = windows.view(first);
      awaitPane(windows, source.hexPanes.get(0), 4);

      ApplicationController target =
          windows.set.moveDocument(
              first, document, null, new WindowSet.Placement(Integer.MAX_VALUE, 4, 5));
      windows.settle("the move");

      assertEquals(HexMode.HEX, target.documents().hexMode());
      FakeView moved = windows.view(target);
      assertEquals(1, moved.hexPanes.size());
      FakeHexView pane = moved.hexPanes.get(0);
      assertEquals(HexMode.HEX, pane.mode);
      awaitPane(windows, pane, 4);
      assertTrue(new CommandStates(target).checked(CommandId.HEX_HEX));
    } finally {
      windows.close();
    }
  }

  @Test
  void aWindowReplacementCreatesThePanesAndViewersAgain() throws Exception {
    Harness harness = new Harness(temp);
    try {
      DocumentController documents = harness.application.documents();
      Path file = Files.writeString(temp.resolve("b.txt"), "0123456789\n", StandardCharsets.UTF_8);
      harness.open(file);
      harness.application.execute(CommandId.HEX_SPLIT);
      final FakeHexView old = harness.view.hexPanes.get(0);
      awaitPane(harness, old, 11);
      old.input.key(HexKey.RIGHT, false);
      documents.hexViewers().open(file);
      harness.await(
          new BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
              return harness.view.hexViewers.size() == 1;
            }
          },
          "the viewer");
      FakeHexView oldViewer = harness.view.hexViewers.get(0);

      FakeView target = new FakeView(harness.ui);
      StagedTabs staged = documents.stageViews(target);
      while (!staged.step()) {
        // One tab per step.
      }
      documents.rebindViews(staged);
      // The replaced pane and window are disposed after the swap.
      old.input.detach(old);
      oldViewer.input.detach(oldViewer);
      harness.ui.runPending();

      assertEquals(2, harness.view.hexPanes.size());
      FakeHexView pane = harness.view.hexPanes.get(1);
      assertSame(target.selected, pane.tab);
      assertEquals(HexMode.SPLIT, pane.mode);
      assertEquals(1, pane.selection.caret());
      assertEquals(HexMode.SPLIT, documents.hexMode());
      pane.input.key(HexKey.RIGHT, false);
      assertEquals(2, pane.selection.caret());
      assertEquals(2, documents.activeEditor().selections().selections().primary().caret());
      assertEquals(2, harness.view.hexViewers.size());
      FakeHexView viewer = harness.view.hexViewers.get(1);
      assertNotSame(oldViewer, viewer);
      assertEquals(oldViewer.title, viewer.title);
      assertEquals(11, viewer.rows.size());
      viewer.input.key(HexKey.SOURCE_END, false);
      assertEquals(11, viewer.selection.caret());
      documents.dispose();
    } finally {
      harness.close();
    }
  }

  @Test
  void theWorkspaceRowMenuOpensFilesInHexViewers() throws Exception {
    Harness harness = new Harness(temp);
    try {
      Path a = Files.write(temp.resolve("a.bin"), new byte[] {1, 2});
      Path b = Files.write(temp.resolve("b.bin"), new byte[] {3});
      WorkspaceSelectionInput rows = harness.application.workspace().selection();
      List<WorkspaceNode> selected =
          List.of(
              new WorkspaceNode(a, "a.bin", WorkspaceNode.Kind.FILE),
              new WorkspaceNode(b, "b.bin", WorkspaceNode.Kind.FILE),
              new WorkspaceNode(temp.resolve("dir"), "dir", WorkspaceNode.Kind.DIRECTORY));
      assertTrue(rows.enabled(WorkspaceSelectionInput.Action.OPEN_HEX, selected, null));
      rows.run(WorkspaceSelectionInput.Action.OPEN_HEX, selected, null);
      harness.await(
          new BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
              return harness.view.hexViewers.size() == 2;
            }
          },
          "two viewers");
      harness.awaitIdle("the viewers");
      assertEquals(2, harness.view.hexViewers.size());
      assertTrue(
          !rows.enabled(
              WorkspaceSelectionInput.Action.OPEN_HEX, List.of(selected.get(2)), selected.get(2)));
      harness.application.documents().dispose();
    } finally {
      harness.close();
    }
  }

  private static void awaitPane(Harness harness, final FakeHexView pane, final long size)
      throws InterruptedException {
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return pane.rows != null
                && pane.rows.size() == size
                && !pane.dimmed
                && harness.view.active.get() == 0;
          }
        },
        "the pane's bytes");
    harness.ui.runPending();
  }
}
