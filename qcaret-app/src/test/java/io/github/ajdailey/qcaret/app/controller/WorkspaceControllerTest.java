package io.github.ajdailey.qcaret.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.view.WorkspaceNode;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.workspace.FileIndex;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The workspace browser's lazy listing, paging, refresh and reveal, and the file index's builds and
 * charge, over a temp tree.
 */
class WorkspaceControllerTest {
  @TempDir Path temp;

  private Harness harness;
  private Path root;

  @BeforeEach
  void create() throws IOException {
    harness = new Harness(temp);
    root = Files.createDirectory(temp.resolve("ws")).toRealPath();
    Files.createDirectories(root.resolve("src").resolve("deep"));
    Files.writeString(root.resolve("src").resolve("Main.java"), "class Main {}\n");
    Files.writeString(root.resolve("src").resolve("deep").resolve("Leaf.java"), "class Leaf {}\n");
    Files.writeString(root.resolve("notes.txt"), "notes\n");
    Files.writeString(root.resolve(".hidden.txt"), "hidden\n");
  }

  @AfterEach
  void close() throws InterruptedException {
    harness.close();
  }

  private WorkspaceController workspace() {
    return harness.application.workspace();
  }

  private void setRoot(final WorkspaceController workspace, final Path folder)
      throws InterruptedException {
    workspace.set(folder);
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return folder.equals(workspace.root()) && workspace.index() != null;
          }
        },
        "workspace set and indexed");
  }

  private static List<String> names(List<WorkspaceNode> nodes) {
    List<String> names = new ArrayList<String>();
    for (WorkspaceNode node : nodes) {
      names.add(node.name());
    }
    return names;
  }

  /** Waits until a directory lists something other than its loading row. */
  private List<WorkspaceNode> listed(final WorkspaceNode directory) throws InterruptedException {
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            List<WorkspaceNode> rows = workspace().children(directory);
            return !rows.isEmpty() && rows.get(0).kind() != WorkspaceNode.Kind.LOADING;
          }
        },
        "listed " + directory.name());
    return workspace().children(directory);
  }

  private static WorkspaceNode child(List<WorkspaceNode> rows, String name) {
    for (WorkspaceNode row : rows) {
      if (row.name().equals(name)) {
        return row;
      }
    }
    throw new AssertionError(name + " not in " + names(rows));
  }

  @Test
  void theBrowserListsADirectoryWhenItExpandsAndRefreshFindsNewFiles() throws Exception {
    setRoot(workspace(), root);
    harness.application.execute(CommandId.WORKSPACE_BROWSER);
    assertTrue(harness.view.workspaceShown);
    assertEquals(root.toString(), workspace().title());
    List<WorkspaceNode> top = listed(workspace().rootNode());
    assertEquals(List.of("src", "notes.txt"), names(top));
    WorkspaceNode src = child(top, "src");
    assertEquals(List.of(), workspace().children(src));
    workspace().expand(src);
    assertEquals(WorkspaceNode.Kind.LOADING, workspace().children(src).get(0).kind());
    assertEquals(List.of("deep", "Main.java"), names(listed(src)));

    Files.writeString(root.resolve("src").resolve("Added.java"), "class Added {}\n");
    final int changes = harness.view.workspaceChanges.size();
    workspace().refresh();
    assertEquals(List.of("deep", "Main.java"), names(workspace().children(src)));
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return workspace().children(src).size() == 3 && !workspace().indexing();
          }
        },
        "refreshed");
    assertEquals(List.of("deep", "Added.java", "Main.java"), names(workspace().children(src)));
    assertTrue(harness.view.workspaceChanges.size() > changes);
    assertEquals(4, workspace().index().size());

    workspace().activate(child(workspace().children(src), "Main.java"));
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return harness.application.documents().count() == 1;
          }
        },
        "opened Main.java");
    workspace().copyPath(src);
    assertEquals(root.resolve("src").toString(), harness.view.clipboard);
    workspace().hide();
    assertFalse(harness.view.workspaceShown);
  }

  @Test
  void aLargeDirectoryShowsAPageAtATime() throws Exception {
    Path big = Files.createDirectory(root.resolve("big"));
    for (int i = 0; i < 1_200; i++) {
      Files.writeString(big.resolve(String.format("f%04d.txt", i)), "");
    }
    setRoot(workspace(), root);
    harness.application.execute(CommandId.WORKSPACE_BROWSER);
    WorkspaceNode dir = child(listed(workspace().rootNode()), "big");
    workspace().expand(dir);
    List<WorkspaceNode> rows = listed(dir);
    assertEquals(WorkspaceController.PAGE + 1, rows.size());
    WorkspaceNode more = rows.get(WorkspaceController.PAGE);
    assertEquals(WorkspaceNode.Kind.MORE, more.kind());
    assertEquals("Show 500 more of 700", more.name());
    workspace().activate(more);
    rows = workspace().children(dir);
    assertEquals("Show 200 more of 200", rows.get(rows.size() - 1).name());
    workspace().activate(rows.get(rows.size() - 1));
    rows = workspace().children(dir);
    assertEquals(1_200, rows.size());
    assertEquals("f1199.txt", rows.get(1_199).name());
  }

  @Test
  void revealListsEachDirectoryOfTheSelectedFile() throws Exception {
    setRoot(workspace(), root);
    harness.application.execute(CommandId.WORKSPACE_BROWSER);
    harness.open(root.resolve("src").resolve("deep").resolve("Leaf.java"));
    workspace().revealActive();
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return harness.view.revealedChain != null;
          }
        },
        "revealed");
    assertEquals(List.of("src", "deep", "Leaf.java"), names(harness.view.revealedChain));
    assertEquals(
        root.resolve("src").resolve("deep").resolve("Leaf.java"),
        harness.view.revealedChain.get(2).path());

    harness.view.revealedChain = null;
    harness.open(Files.writeString(temp.resolve("outside.txt"), "x\n"));
    workspace().revealActive();
    harness.ui.runPending();
    assertNull(harness.view.revealedChain);
    assertEquals(
        "outside.txt is not below the workspace folder",
        harness.view.notices.get(harness.view.notices.size() - 1));
  }

  @Test
  void anIndexIsChargedUntilReplacedOrDisposed() throws Exception {
    MemoryBudget budget = new MemoryBudget(1L << 30, 60);
    WorkspaceController workspace =
        new WorkspaceController(
            harness.view, harness.workers, harness.ui, harness.application.documents(), budget);
    setRoot(workspace, root);
    FileIndex first = workspace.index();
    assertEquals(3, first.size());
    assertEquals(first.charged(), budget.reserved());
    Path other = Files.createDirectory(temp.resolve("other")).toRealPath();
    Files.writeString(other.resolve("only.txt"), "");
    setRoot(workspace, other);
    assertEquals(1, workspace.index().size());
    assertEquals(workspace.index().charged(), budget.reserved());
    workspace.refresh();
    workspace.dispose();
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return budget.reserved() == 0;
          }
        },
        "charge returned");
    assertNull(workspace.index());
  }

  /** QLogg SWT-8: Windows decides by extension; POSIX by the executable bit. */
  @Test
  void openInDefaultApplicationRefusesFilesTheSystemWouldRun() throws Exception {
    assertTrue(WorkspaceController.runsInsteadOfOpening(Path.of("setup.EXE"), true));
    assertTrue(WorkspaceController.runsInsteadOfOpening(Path.of("run.ps1"), true));
    assertFalse(WorkspaceController.runsInsteadOfOpening(root.resolve("notes.txt"), true));
    setRoot(workspace(), root);
    harness.view.windows = true;
    workspace()
        .openInDefaultApplication(
            new WorkspaceNode(root.resolve("tool.cmd"), "tool.cmd", WorkspaceNode.Kind.FILE));
    workspace()
        .openInDefaultApplication(
            new WorkspaceNode(root.resolve("notes.txt"), "notes.txt", WorkspaceNode.Kind.FILE));
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return harness.view.launched.size() == 1
                && harness.view.notices.contains(
                    "tool.cmd was not opened: the system would run it");
          }
        },
        "launched one, refused one");
    assertEquals(root.resolve("notes.txt").toString(), harness.view.launched.get(0));

    Path script = Files.writeString(root.resolve("run.sh"), "#!/bin/sh\n");
    assumeTrue(
        Files.getFileStore(script).supportsFileAttributeView("posix"), "POSIX permissions only");
    Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
    assertTrue(WorkspaceController.runsInsteadOfOpening(script, false));
    assertFalse(WorkspaceController.runsInsteadOfOpening(root.resolve("notes.txt"), false));
  }
}
