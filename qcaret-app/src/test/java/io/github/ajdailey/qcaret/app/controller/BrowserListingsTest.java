package io.github.ajdailey.qcaret.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.view.WorkspaceNode;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The workspace browser's listings through {@link WorkspaceController}: hidden browser, empty and
 * unreadable rows, reveal past a page, excluded directories, a row operation's relist, restored
 * expansion and a listing that ends after dispose.
 */
class BrowserListingsTest {
  @TempDir Path temp;

  private Harness harness;
  private Path root;

  @BeforeEach
  void create() throws Exception {
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

  private void setRoot() throws InterruptedException {
    workspace().set(root);
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return root.equals(workspace().root()) && workspace().index() != null;
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

  private static WorkspaceNode directory(Path path) {
    return new WorkspaceNode(path, path.getFileName().toString(), WorkspaceNode.Kind.DIRECTORY);
  }

  /** Waits until a directory shows the names given. */
  private void shows(final WorkspaceNode directory, final List<String> expected)
      throws InterruptedException {
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return expected.equals(names(workspace().children(directory)));
          }
        },
        directory.name() + " shows " + expected);
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

  @Test
  void aHiddenBrowserListsNothingUntilItShows() throws Exception {
    setRoot();
    assertEquals(List.of(), workspace().children(workspace().rootNode()));
    harness.view.expanded.add(directory(root.resolve("src")));
    assertEquals(List.of(), workspace().listings().expandedDirectories());
    harness.application.execute(CommandId.WORKSPACE_BROWSER);
    shows(workspace().rootNode(), List.of("src", "notes.txt"));
    assertEquals(
        List.of(root.resolve("src").toString()), workspace().listings().expandedDirectories());
  }

  @Test
  void anEmptyDirectoryAndAnUnreadableOneShowOneRow() throws Exception {
    Files.createDirectory(root.resolve("empty"));
    setRoot();
    harness.application.execute(CommandId.WORKSPACE_BROWSER);
    listed(workspace().rootNode());
    WorkspaceNode empty = directory(root.resolve("empty"));
    workspace().expand(empty);
    List<WorkspaceNode> rows = listed(empty);
    assertEquals(1, rows.size());
    assertEquals(WorkspaceNode.Kind.EMPTY, rows.get(0).kind());
    assertEquals("(empty)", rows.get(0).name());

    WorkspaceNode gone = directory(root.resolve("gone"));
    workspace().expand(gone);
    rows = listed(gone);
    assertEquals(1, rows.size());
    assertEquals(WorkspaceNode.Kind.ERROR, rows.get(0).kind());
    assertTrue(rows.get(0).name().startsWith("Cannot list: "), rows.get(0).name());
  }

  @Test
  void revealShowsEnoughPagesAndRefusesAnEntryTheListingLeavesOut() throws Exception {
    Path big = Files.createDirectory(root.resolve("big"));
    for (int i = 0; i < 1_200; i++) {
      Files.writeString(big.resolve(String.format("f%04d.txt", i)), "");
    }
    setRoot();
    harness.application.execute(CommandId.WORKSPACE_BROWSER);
    harness.open(big.resolve("f1100.txt"));
    workspace().revealActive();
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return harness.view.revealedChain != null;
          }
        },
        "revealed");
    assertEquals(List.of("big", "f1100.txt"), names(harness.view.revealedChain));
    List<WorkspaceNode> rows = workspace().children(directory(big));
    assertEquals(1_200, rows.size());
    assertEquals(WorkspaceNode.Kind.FILE, rows.get(1_199).kind());

    harness.view.revealedChain = null;
    harness.open(root.resolve(".hidden.txt"));
    workspace().revealActive();
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return harness.view.notices.contains(
                ".hidden.txt is hidden or in an excluded directory");
          }
        },
        "refused");
    assertNull(harness.view.revealedChain);
  }

  @Test
  void excludingADirectoryListsAndIndexesAgain() throws Exception {
    setRoot();
    harness.application.execute(CommandId.WORKSPACE_BROWSER);
    shows(workspace().rootNode(), List.of("src", "notes.txt"));
    workspace().setExcludedDirectories("src");
    shows(workspace().rootNode(), List.of("notes.txt"));
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !workspace().indexing() && workspace().index().size() == 1;
          }
        },
        "indexed without src");
  }

  @Test
  void aRowOperationDropsListingsUnderARemovedFolderAndSelectsTheNewRow() throws Exception {
    setRoot();
    harness.application.execute(CommandId.WORKSPACE_BROWSER);
    listed(workspace().rootNode());
    Path src = root.resolve("src");
    Path deep = src.resolve("deep");
    workspace().expand(directory(src));
    listed(directory(src));
    workspace().expand(directory(deep));
    listed(directory(deep));

    Files.delete(deep.resolve("Leaf.java"));
    Files.delete(deep);
    workspace().changed(src, deep, null, true, null);
    assertEquals(List.of(), workspace().children(directory(deep)));
    shows(directory(src), List.of("Main.java"));
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !workspace().indexing() && workspace().index().size() == 2;
          }
        },
        "indexed without deep");

    Path added = Files.writeString(src.resolve("Added.java"), "class Added {}\n");
    workspace().changed(src, null, added, false, added);
    assertEquals(3, workspace().index().size());
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return harness.view.revealedChain != null;
          }
        },
        "selected");
    assertEquals(List.of("src", "Added.java"), names(harness.view.revealedChain));
    assertEquals(added, harness.view.revealedChain.get(1).path());
  }

  @Test
  void restoredDirectoriesExpandOnceTheirParentIsListed() throws Exception {
    Path src = root.resolve("src");
    Path deep = src.resolve("deep");
    workspace().restored(root, null, true, List.of(src, deep));
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return harness.view.expanded.size() == 1;
          }
        },
        "src expanded");
    assertEquals(src, harness.view.expanded.get(0).path());
    workspace().expand(harness.view.expanded.get(0));
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return harness.view.expanded.size() == 2;
          }
        },
        "deep expanded");
    assertEquals(deep, harness.view.expanded.get(1).path());
  }

  @Test
  void aListingThatEndsAfterDisposeIsDropped() throws Exception {
    final CountDownLatch started = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);
    final AtomicBoolean timedOut = new AtomicBoolean();
    MemoryBudget budget = new MemoryBudget(1L << 30, 60);
    final WorkspaceController workspace =
        new WorkspaceController(
            harness.view, harness.workers, harness.ui, harness.application.documents(), budget);
    workspace
        .listings()
        .setBeforeList(
            new Runnable() {
              @Override
              public void run() {
                started.countDown();
                try {
                  timedOut.set(!release.await(20, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
              }
            });
    workspace.toggle();
    workspace.set(root);
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return started.getCount() == 0;
          }
        },
        "listing started");
    workspace.dispose();
    final int changes = harness.view.workspaceChanges.size();
    release.countDown();
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !harness.workers.liveThreadNames().contains("qcaret-io-")
                && budget.reserved() == 0;
          }
        },
        "the listing and the index ended");
    harness.ui.runPending();
    assertFalse(timedOut.get());
    assertEquals(changes, harness.view.workspaceChanges.size());
    assertEquals(List.of(), workspace.children(workspace.rootNode()));
  }
}
