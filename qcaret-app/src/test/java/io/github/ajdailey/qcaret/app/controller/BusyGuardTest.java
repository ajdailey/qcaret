package io.github.ajdailey.qcaret.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.view.WorkspaceNode;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
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
 * The workspace browser's Rename and Delete against work in flight: {@link OpenFile#inFlight} on a
 * document under the entry, and a {@link RunningWrites} target under it. A write held open by a
 * gate charset or by the UI queue keeps its target registered.
 */
class BusyGuardTest {
  @TempDir Path temp;

  private Harness harness;
  private Path root;
  private Path out;

  @BeforeEach
  void create() throws Exception {
    harness = new Harness(temp);
    root = Files.createDirectory(temp.resolve("ws")).toRealPath();
    out = Files.createDirectory(root.resolve("out"));
    Files.createDirectory(root.resolve("src"));
    Files.writeString(root.resolve("src").resolve("Main.java"), "class Main {}\n");
    Files.writeString(root.resolve("notes.txt"), "notes\n");
    final WorkspaceController workspace = workspace();
    workspace.set(root);
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return root.equals(workspace.root()) && workspace.index() != null;
          }
        },
        "workspace set and indexed");
    harness.application.execute(CommandId.WORKSPACE_BROWSER);
  }

  @AfterEach
  void close() throws InterruptedException {
    harness.close();
  }

  private WorkspaceController workspace() {
    return harness.application.workspace();
  }

  private DocumentController documents() {
    return harness.application.documents();
  }

  private static WorkspaceNode node(Path path, WorkspaceNode.Kind kind) {
    return new WorkspaceNode(path, path.getFileName().toString(), kind);
  }

  /** Opens a file in a gate charset, so a write's encoder can be held. */
  private GatedCharset openGated(Path file) throws Exception {
    harness.open(file);
    final GatedCharset gate = new GatedCharset();
    documents().reopenWithEncoding(gate);
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return documents().activeDocument().encoding().charset().equals(gate);
          }
        },
        "reopened");
    harness.awaitIdle("reopen");
    return gate;
  }

  /** Renames {@code folder} to {@code moved}; asserts the busy notice and nothing moved. */
  private void assertRenameRefused(Path folder) {
    int notices = harness.view.notices.size();
    workspace().files().rename(node(folder, WorkspaceNode.Kind.DIRECTORY));
    assertNotNull(harness.view.fileNameAnswer, "asked for a name");
    harness.view.fileNameAnswer.accept("moved");
    harness.view.fileNameAnswer = null;
    assertEquals(
        List.of(UiText.FILE_BUSY.format(folder.getFileName())),
        harness.view.notices.subList(notices, harness.view.notices.size()));
    assertTrue(Files.isDirectory(folder));
  }

  /** Deletes {@code folder}; asserts the busy notice, no prompt and nothing deleted. */
  private void assertDeleteRefused(final Path folder) throws InterruptedException {
    final int notices = harness.view.notices.size();
    harness.view.deleteAsked = null;
    workspace().files().delete(node(folder, WorkspaceNode.Kind.DIRECTORY));
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return harness.view.notices.size() > notices || harness.view.deleteAsked != null;
          }
        },
        "refused or asked");
    assertNull(harness.view.deleteAsked, "the delete asked");
    assertEquals(UiText.FILE_BUSY.format(folder.getFileName()), harness.view.notices.get(notices));
    assertTrue(Files.isDirectory(folder));
  }

  @Test
  void inFlightNamesEachWorkOnTheFile() {
    OpenFile file = new OpenFile(null, null);
    assertTrue(file.inFlight().isEmpty());
    file.saving = new CancellationToken();
    file.copying = new CancellationToken();
    file.reloading = new CancellationToken();
    file.changePrompt = true;
    file.renaming = true;
    assertEquals(EnumSet.allOf(OpenFile.Work.class), file.inFlight());
    file.saving = null;
    file.copying = null;
    file.changePrompt = false;
    assertEquals(EnumSet.of(OpenFile.Work.RELOAD, OpenFile.Work.RENAME), file.inFlight());
  }

  @Test
  void aFolderRenameAndDeleteAreRefusedWhileSaveAsWritesIntoIt() throws Exception {
    final GatedCharset gate = openGated(root.resolve("notes.txt"));
    documents().activeEditor().type("x ");
    gate.armEncoder();
    harness.view.saveChooser = null;
    documents().saveAs();
    assertNotNull(harness.view.saveChooser, "Save As asks for a file");
    Path target = out.resolve("copy.txt");
    harness.view.saveChooser.accept(target);
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return gate.held();
          }
        },
        "the write started");
    try {
      assertRenameRefused(out);
      assertDeleteRefused(out);
    } finally {
      gate.release();
    }
    harness.awaitIdle("save as");
    assertEquals("x notes\n", Files.readString(target));
    assertEquals(target, documents().activePath());
  }

  @Test
  void aFolderRenameIsRefusedWhileSaveACopyAsWritesIntoIt() throws Exception {
    final GatedCharset gate = openGated(root.resolve("notes.txt"));
    gate.armEncoder();
    harness.view.saveChooser = null;
    harness.application.execute(CommandId.SAVE_COPY_AS);
    assertNotNull(harness.view.saveChooser, "Save a Copy As asks for a file");
    final Path target = out.resolve("copy.txt");
    harness.view.saveChooser.accept(target);
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return gate.held();
          }
        },
        "the copy started");
    try {
      assertRenameRefused(out);
    } finally {
      gate.release();
    }
    harness.awaitIdle("copy");
    assertEquals("notes\n", Files.readString(target));
    assertTrue(
        harness.view.notices.contains(UiText.SAVE_COPY_DONE.format("notes.txt", target)),
        harness.view.notices.toString());
    workspace().files().rename(node(out, WorkspaceNode.Kind.DIRECTORY));
    harness.view.fileNameAnswer.accept("moved");
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return Files.isDirectory(root.resolve("moved"));
          }
        },
        "renamed once the copy ended");
  }

  /** The list's write posts its end to the UI queue, which holds it until the test runs it. */
  @Test
  void aFolderRenameIsRefusedUntilAChecksumListSavedIntoItEnds() throws Exception {
    Path hashed = Files.writeString(root.resolve("hashed.txt"), "abc");
    harness.application.execute(CommandId.SHA256_FILES);
    harness.view.openChooser.accept(List.of(hashed));
    final FakeView.Checksums shown = harness.view.checksums;
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return shown.status != null;
          }
        },
        "hashed");
    shown.input.saveAs();
    final Path target = out.resolve("SHA256SUMS");
    harness.view.saveChooser.accept(target);
    assertRenameRefused(out);
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !documents().writes().under(new BrowserPath(out, out));
          }
        },
        "the list's write ended");
    assertTrue(Files.exists(target));
    workspace().files().rename(node(out, WorkspaceNode.Kind.DIRECTORY));
    harness.view.fileNameAnswer.accept("moved");
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return Files.isDirectory(root.resolve("moved"));
          }
        },
        "renamed once the write ended");
  }

  @Test
  void aFolderRenameIsRefusedWhileARenameHoldsADocumentInIt() throws Exception {
    final Path main = root.resolve("src").resolve("Main.java");
    harness.open(main);
    final AtomicBoolean first = new AtomicBoolean(true);
    final CountDownLatch entered = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);
    workspace()
        .files()
        .setBeforeRename(
            new Runnable() {
              @Override
              public void run() {
                if (!first.compareAndSet(true, false)) {
                  return;
                }
                entered.countDown();
                try {
                  release.await(Harness.BOUND.toSeconds(), TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
              }
            });
    workspace().files().rename(node(main, WorkspaceNode.Kind.FILE));
    harness.view.fileNameAnswer.accept("App.java");
    harness.view.fileNameAnswer = null;
    final Path renamed = root.resolve("src").resolve("App.java");
    try {
      assertTrue(entered.await(Harness.BOUND.toSeconds(), TimeUnit.SECONDS), "rename started");
      assertRenameRefused(root.resolve("src"));
    } finally {
      release.countDown();
    }
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return renamed.equals(documents().activePath());
          }
        },
        "the file rename landed");
    assertTrue(Files.exists(renamed));
  }

  /** A target named with {@code ..} or relative to the working directory still matches. */
  @Test
  void runningWritesMatchTargetsUnderAnEntry() throws IOException {
    RunningWrites writes = new RunningWrites();
    BrowserPath folder = new BrowserPath(out, out.toRealPath());
    Path target = out.resolve("..").resolve("out").resolve("a.txt");
    writes.started(target);
    assertTrue(writes.under(folder));
    assertTrue(writes.under(new BrowserPath(root, root)));
    assertFalse(writes.under(new BrowserPath(root.resolve("src"), root.resolve("src"))));
    writes.started(target);
    writes.ended(target);
    assertTrue(writes.under(folder), "one of two writes to a target runs");
    writes.ended(target);
    assertFalse(writes.under(folder));
  }
}
