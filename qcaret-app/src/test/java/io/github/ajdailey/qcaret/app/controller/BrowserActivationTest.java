package io.github.ajdailey.qcaret.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.view.WorkspaceNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link BrowserActivation}: window activation lists the browser's directories again, and an
 * activation while a read runs starts none.
 */
class BrowserActivationTest {
  @TempDir Path temp;

  private Harness harness;
  private Path root;
  private WorkspaceController workspace;

  @BeforeEach
  void create() throws Exception {
    harness = new Harness(temp);
    root = Files.createDirectory(temp.resolve("ws")).toRealPath();
    Files.createDirectory(root.resolve("src"));
    Files.writeString(root.resolve("src").resolve("Main.java"), "class Main {}\n");
    workspace = harness.application.workspace();
    workspace.set(root);
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return root.equals(workspace.root()) && workspace.index() != null;
          }
        },
        "workspace set");
    harness.application.execute(CommandId.WORKSPACE_BROWSER);
    WorkspaceNode src = child(listed(workspace.rootNode(), 1), "src");
    workspace.expand(src);
    listed(src, 1);
  }

  @AfterEach
  void close() throws InterruptedException {
    harness.close();
  }

  @Test
  void aFileCreatedWhileInactiveIsListedOnActivation() throws Exception {
    Files.writeString(root.resolve("src").resolve("Added.java"), "class Added {}\n");
    Files.writeString(root.resolve("top.txt"), "top\n");
    harness.application.windowActivated();
    WorkspaceNode src = child(workspace.children(workspace.rootNode()), "src");
    assertEquals(List.of("Added.java", "Main.java"), names(listed(src, 2)));
    assertEquals(List.of("src", "top.txt"), names(listed(workspace.rootNode(), 2)));
  }

  @Test
  void anActivationWhileAReadRunsStartsNone() throws Exception {
    final AtomicInteger reads = new AtomicInteger();
    final CountDownLatch release = new CountDownLatch(1);
    workspace
        .listings()
        .setBeforeList(
            new Runnable() {
              @Override
              public void run() {
                reads.incrementAndGet();
                try {
                  release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
              }
            });
    harness.application.windowActivated();
    assertEquals(0, workspace.listings().idleDirectories().size());
    harness.application.windowActivated();
    harness.application.windowActivated();
    release.countDown();
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return workspace.listings().idleDirectories().size() == 2;
          }
        },
        "reads done");
    assertEquals(2, reads.get());
  }

  /** Waits until a directory lists a number of rows that are not a loading row. */
  private List<WorkspaceNode> listed(final WorkspaceNode directory, final int rows)
      throws InterruptedException {
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            List<WorkspaceNode> shown = workspace.children(directory);
            return shown.size() == rows && shown.get(0).kind() != WorkspaceNode.Kind.LOADING;
          }
        },
        "listed " + directory.name());
    return workspace.children(directory);
  }

  private static WorkspaceNode child(List<WorkspaceNode> rows, String name) {
    for (WorkspaceNode row : rows) {
      if (row.name().equals(name)) {
        return row;
      }
    }
    throw new AssertionError(name + " not in " + names(rows));
  }

  private static List<String> names(List<WorkspaceNode> nodes) {
    List<String> names = new ArrayList<String>();
    for (WorkspaceNode node : nodes) {
      names.add(node.name());
    }
    return names;
  }
}
