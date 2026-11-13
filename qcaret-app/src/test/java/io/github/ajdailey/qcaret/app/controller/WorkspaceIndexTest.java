package io.github.ajdailey.qcaret.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.command.CommandId;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The workspace file index through {@link WorkspaceController}: its listeners after a build and
 * after a change made in place, and a build that replaces a running one.
 */
class WorkspaceIndexTest {
  @TempDir Path temp;

  private Harness harness;
  private Path root;

  @BeforeEach
  void create() throws Exception {
    harness = new Harness(temp);
    root = Files.createDirectory(temp.resolve("ws")).toRealPath();
    Files.writeString(root.resolve("one.txt"), "one\n");
    Files.writeString(root.resolve("two.txt"), "two\n");
  }

  @AfterEach
  void close() throws InterruptedException {
    harness.close();
  }

  private WorkspaceController workspace() {
    return harness.application.workspace();
  }

  private void awaitIndexed(final int files, String what) throws InterruptedException {
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !workspace().indexing()
                && workspace().index() != null
                && workspace().index().size() == files;
          }
        },
        what);
  }

  @Test
  void listenersRunAfterABuildAndAnInPlaceChangeUntilRemoved() throws Exception {
    final AtomicInteger runs = new AtomicInteger();
    Runnable listener =
        new Runnable() {
          @Override
          public void run() {
            runs.incrementAndGet();
          }
        };
    workspace().addIndexListener(listener);
    workspace().set(root);
    awaitIndexed(2, "indexed");
    assertEquals(1, runs.get());
    harness.application.execute(CommandId.WORKSPACE_BROWSER);
    Path added = Files.writeString(root.resolve("three.txt"), "three\n");
    workspace().changed(root, null, added, false, null);
    assertEquals(3, workspace().index().size());
    assertEquals(2, runs.get());
    workspace().removeIndexListener(listener);
    Files.delete(added);
    workspace().changed(root, added, null, false, null);
    assertEquals(2, workspace().index().size());
    assertEquals(2, runs.get());
  }

  @Test
  void aRefreshDuringABuildReplacesItAndKeepsOneIndex() throws Exception {
    workspace().set(root);
    awaitIndexed(2, "indexed");
    harness.application.execute(CommandId.WORKSPACE_BROWSER);
    Files.writeString(root.resolve("three.txt"), "three\n");
    workspace().refresh();
    assertTrue(workspace().indexing());
    workspace().refresh();
    awaitIndexed(3, "indexed again");
    harness.ui.runPending();
    assertEquals(3, workspace().index().size());
  }
}
