package io.github.ajdailey.qcaret.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.config.SettingsLocation;
import io.github.ajdailey.qcaret.app.config.SettingsMode;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.recovery.RecoveryFile;
import io.github.ajdailey.qcaret.app.recovery.RecoveryStore;
import io.github.ajdailey.qcaret.app.session.SessionStore;
import io.github.ajdailey.qcaret.app.view.SearchScope;
import io.github.ajdailey.qcaret.app.view.WorkspaceNode;
import io.github.ajdailey.qcaret.core.document.LoadReservation;
import io.github.ajdailey.qcaret.core.document.SelectionSet;
import io.github.ajdailey.qcaret.core.workspace.FileIndex;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The workspace browser's Search in Folder, New File, New Folder, Rename and Delete over a temp
 * tree: names refused, open documents that follow a rename with their edits, recent entries and
 * recovery file, and a delete that leaves an open document marked missing.
 */
class WorkspaceFilesTest {
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

  private static WorkspaceNode file(Path path) {
    return new WorkspaceNode(path, path.getFileName().toString(), WorkspaceNode.Kind.FILE);
  }

  private static WorkspaceNode folder(Path path) {
    return new WorkspaceNode(path, path.getFileName().toString(), WorkspaceNode.Kind.DIRECTORY);
  }

  private void await(BooleanSupplier condition, String what) throws InterruptedException {
    harness.await(condition, what);
  }

  private void awaitInfo(final int before, String what) throws InterruptedException {
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return harness.view.infos.size() > before;
          }
        },
        what);
  }

  /** Runs UI tasks until the held rename is the only operation the window shows as running. */
  private void awaitOnlyTheRename(String what) throws InterruptedException {
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return harness.view.active.get() == 1 && documents().loading() == 0;
          }
        },
        what);
    harness.ui.runPending();
  }

  private String lastInfo() {
    return harness.view.infos.get(harness.view.infos.size() - 1);
  }

  private List<String> indexed() {
    List<String> paths = new ArrayList<String>();
    FileIndex index = workspace().index();
    for (int i = 0; i < index.size(); i++) {
      paths.add(index.path(i));
    }
    return paths;
  }

  private void answer(String name) {
    assertNotNull(harness.view.fileNameAnswer, "asked for a name");
    harness.view.fileNameAnswer.accept(name);
    harness.view.fileNameAnswer = null;
  }

  @Test
  void searchInFolderOpensHyperSearchOnTheFolderAndItsSubdirectories() {
    workspace().files().searchInFolder(folder(root.resolve("src")));
    assertEquals(SearchScope.TREE, harness.view.searchForm.scope());
    assertEquals(root.resolve("src").toString(), harness.view.searchForm.directory());
    workspace().files().searchInFolder(file(root.resolve("src").resolve("Main.java")));
    assertEquals(root.resolve("src").toString(), harness.view.searchForm.directory());
  }

  @Test
  void newFileOpensTheFileAndNewFolderIsSelected() throws Exception {
    workspace().files().newFile(workspace().rootNode());
    assertTrue(harness.view.fileNameAsked.startsWith(UiText.NEW_FILE_TITLE.text()));
    answer("made.txt");
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return documents().count() == 1 && indexed().contains("made.txt");
          }
        },
        "made.txt opened and indexed");
    assertEquals(root.resolve("made.txt"), documents().activePath());
    assertTrue(Files.isRegularFile(root.resolve("made.txt")));

    workspace().files().newFolder(folder(root.resolve("src")));
    answer("lib");
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            List<WorkspaceNode> chain = harness.view.revealedChain;
            return chain != null
                && chain.get(chain.size() - 1).path().equals(root.resolve("src").resolve("lib"));
          }
        },
        "lib selected");
    assertTrue(Files.isDirectory(root.resolve("src").resolve("lib")));
  }

  @Test
  void aNameThatIsNotOneFileNameOrIsTakenIsRefused() throws Exception {
    for (String name : List.of("", "  ", ".", "..", "a/b", "a\\b")) {
      int before = harness.view.infos.size();
      workspace().files().newFile(workspace().rootNode());
      answer(name);
      assertEquals(before + 1, harness.view.infos.size(), name);
      assertTrue(lastInfo().contains(UiText.FILE_NAME_INVALID.format(name)), lastInfo());
    }
    int before = harness.view.infos.size();
    workspace().files().newFolder(workspace().rootNode());
    answer("notes.txt");
    awaitInfo(before, "refused");
    assertTrue(lastInfo().contains(UiText.FILE_EXISTS.format("notes.txt")), lastInfo());
    assertTrue(Files.isRegularFile(root.resolve("notes.txt")));

    before = harness.view.infos.size();
    workspace().files().rename(file(root.resolve("notes.txt")));
    assertEquals("notes.txt", harness.view.fileNameInitial);
    assertEquals("notes".length(), harness.view.fileNameSelectEnd);
    answer("src");
    awaitInfo(before, "rename refused");
    assertTrue(lastInfo().contains(UiText.FILE_EXISTS.format("src")), lastInfo());
    assertEquals("notes\n", Files.readString(root.resolve("notes.txt")));
  }

  @Test
  void anOpenDirtyDocumentFollowsARenamedFolderAndFile() throws Exception {
    final Path main = root.resolve("src").resolve("Main.java");
    harness.open(main);
    documents().activeEditor().type("x ");
    workspace().files().rename(folder(root.resolve("src")));
    answer("lib");
    final Path moved = root.resolve("lib").resolve("Main.java");
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return moved.equals(documents().activePath());
          }
        },
        "document followed the folder");
    assertEquals(moved, documents().activeOpenedPath());
    assertTrue(documents().activeDocument().isDirty());
    assertEquals("*Main.java", harness.view.selected.title);
    assertEquals(moved.toString(), harness.view.selected.toolTip);
    assertTrue(harness.application.recentFiles().paths().contains(moved));
    assertFalse(harness.application.recentFiles().paths().contains(main));

    workspace().files().rename(file(moved));
    answer("Other.java");
    final Path other = root.resolve("lib").resolve("Other.java");
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return other.equals(documents().activePath());
          }
        },
        "document followed the file");
    assertEquals("*Other.java", harness.view.selected.title);
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return indexed().contains("lib/Main.java") || indexed().contains("lib/Other.java");
          }
        },
        "index rebuilt after the folder rename");

    documents().save();
    harness.awaitIdle("save");
    assertEquals("x class Main {}\n", Files.readString(other));
    assertFalse(Files.exists(main));
    assertFalse(Files.exists(moved));
    assertFalse(documents().activeDocument().isDirty());
  }

  @Test
  void aFolderRenameMovesTheRememberedStateOfAClosedFile() throws Exception {
    Path main = root.resolve("src").resolve("Main.java");
    harness.open(main);
    documents().activeEditor().selections().set(SelectionSet.caret(6));
    documents().closeActive();
    harness.ui.runPending();
    workspace().files().rename(folder(root.resolve("src")));
    answer("lib");
    final Path moved = root.resolve("lib").resolve("Main.java");
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return harness.application.recentFiles().paths().contains(moved);
          }
        },
        "renamed");
    harness.open(moved);
    assertEquals(6, documents().activeEditor().selections().selections().primary().start());
  }

  @Test
  void aCaseOnlyRenameMovesTheOpenDocument() throws Exception {
    final Path notes = root.resolve("notes.txt");
    harness.open(notes);
    workspace().files().rename(file(notes));
    answer("Notes.txt");
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            Path active = documents().activePath();
            return active != null && active.getFileName().toString().equals("Notes.txt");
          }
        },
        "case-only rename followed");
    assertTrue(names(root).contains("Notes.txt"), names(root).toString());
    assertFalse(names(root).contains("notes.txt"), names(root).toString());
    assertEquals("Notes.txt", harness.view.selected.title);
  }

  private static List<String> names(Path directory) throws IOException {
    List<String> names = new ArrayList<String>();
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
      for (Path entry : stream) {
        names.add(String.valueOf(entry.getFileName()));
      }
    }
    return names;
  }

  @Test
  void deletingAFolderAsksWithItsCountsAndLeavesADirtyDocumentMissing() throws Exception {
    Path main = root.resolve("src").resolve("Main.java");
    harness.open(main);
    documents().activeEditor().type("x ");
    workspace().files().delete(folder(root.resolve("src")));
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return harness.view.deleteAsked != null;
          }
        },
        "asked");
    assertEquals(
        UiText.DELETE_FOLDER_MESSAGE.format("src", 2, 1)
            + "\n\n"
            + UiText.DELETE_UNSAVED.format("Main.java"),
        harness.view.deleteAsked);
    assertTrue(Files.exists(main), "nothing goes before the answer");
    harness.view.deleteAccept.run();
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !Files.exists(root.resolve("src"))
                && UiText.TAB_MISSING.format("*Main.java").equals(harness.view.selected.title);
          }
        },
        "deleted, document missing");
    assertEquals(1, documents().count());
    assertTrue(documents().activeDocument().isDirty());
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return indexed().equals(List.of("notes.txt"));
          }
        },
        "index rebuilt");
  }

  @Test
  void deletingAFileAsksAndRemovesItFromTheIndex() throws Exception {
    workspace().files().delete(file(root.resolve("notes.txt")));
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return harness.view.deleteAsked != null;
          }
        },
        "asked");
    assertEquals(UiText.DELETE_FILE_MESSAGE.format("notes.txt"), harness.view.deleteAsked);
    harness.view.deleteAccept.run();
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !Files.exists(root.resolve("notes.txt")) && !indexed().contains("notes.txt");
          }
        },
        "deleted and unindexed");
  }

  @Test
  void deletingALinkToAnOutsideFolderRemovesOnlyTheLink() throws Exception {
    Path outside = Files.createDirectory(temp.resolve("outside"));
    Path kept = Files.writeString(outside.resolve("kept.txt"), "kept\n");
    final Path link;
    try {
      link = Files.createSymbolicLink(root.resolve("ext"), outside);
    } catch (IOException | UnsupportedOperationException e) {
      assumeTrue(false, "no symbolic links: " + e);
      return;
    }
    workspace().files().delete(folder(link));
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return harness.view.deleteAsked != null;
          }
        },
        "asked");
    assertEquals(UiText.DELETE_LINK_MESSAGE.format("ext"), harness.view.deleteAsked);
    harness.view.deleteAccept.run();
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !Files.exists(link, LinkOption.NOFOLLOW_LINKS);
          }
        },
        "link deleted");
    assertEquals("kept\n", Files.readString(kept));
  }

  @Test
  void theRootTakesNoRenameOrDelete() {
    workspace().files().rename(workspace().rootNode());
    workspace().files().delete(workspace().rootNode());
    harness.ui.runPending();
    assertNull(harness.view.fileNameAnswer);
    assertNull(harness.view.deleteAsked);
    assertTrue(Files.isDirectory(root));
  }

  /** Starts the session and recovery; returns the recovery directory. */
  private Path startRecovery() throws Exception {
    Path settings = Files.createDirectories(temp.resolve("settings"));
    SessionStore store = new SessionStore(new SettingsLocation(SettingsMode.EXPLICIT, settings));
    harness.application.startSession(store, store.load(), false);
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return harness.application.recovery().idle();
          }
        },
        "started");
    harness.awaitIdle("start");
    return settings.resolve(RecoveryStore.DIRECTORY);
  }

  /**
   * A save started while the rename runs would write the source name: the document would turn
   * clean, then follow to the new name, which holds the text before the edit.
   */
  @Test
  void saveAndReloadAreRefusedWhileARenameRunsAndTheEditsFollow() throws Exception {
    final Path directory = startRecovery();
    final Path notes = root.resolve("notes.txt");
    harness.open(notes);
    documents().activeEditor().type("x ");
    final CountDownLatch entered = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);
    workspace()
        .files()
        .setBeforeRename(
            new Runnable() {
              @Override
              public void run() {
                entered.countDown();
                try {
                  release.await(Harness.BOUND.toSeconds(), TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
              }
            });
    workspace().files().rename(file(notes));
    answer("renamed.txt");
    final Path renamed = root.resolve("renamed.txt");
    try {
      assertTrue(entered.await(Harness.BOUND.toSeconds(), TimeUnit.SECONDS), "rename started");
      int infos = harness.view.infos.size();
      documents().save();
      documents().reloadFromDisk();
      awaitOnlyTheRename("save refused");
      assertEquals("notes\n", Files.readString(notes));
      assertTrue(documents().activeDocument().isDirty());
      assertEquals(infos + 1, harness.view.infos.size(), harness.view.infos.toString());
      assertTrue(lastInfo().contains(UiText.RENAME_RUNNING.format("notes.txt")), lastInfo());
      harness.ui.runLongTimers();
      awaitHeader(directory, notes);
    } finally {
      release.countDown();
    }
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return renamed.equals(documents().activePath());
          }
        },
        "renamed");
    assertFalse(Files.exists(notes));
    assertEquals("notes\n", Files.readString(renamed));
    assertTrue(documents().activeDocument().isDirty());
    harness.ui.runLongTimers();
    awaitHeader(directory, renamed);
    documents().save();
    harness.awaitIdle("save");
    assertEquals("x notes\n", Files.readString(renamed));
    assertFalse(Files.exists(notes));
  }

  @Test
  void aReloadOfACleanDocumentIsRefusedWhileARenameRuns() throws Exception {
    final Path notes = root.resolve("notes.txt");
    harness.open(notes);
    final CountDownLatch entered = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);
    workspace()
        .files()
        .setBeforeRename(
            new Runnable() {
              @Override
              public void run() {
                entered.countDown();
                try {
                  release.await(Harness.BOUND.toSeconds(), TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
              }
            });
    workspace().files().rename(file(notes));
    answer("renamed.txt");
    final Path renamed = root.resolve("renamed.txt");
    try {
      assertTrue(entered.await(Harness.BOUND.toSeconds(), TimeUnit.SECONDS), "rename started");
      int notices = harness.view.notices.size();
      documents().reloadFromDisk();
      assertEquals(
          List.of(UiText.RENAME_RUNNING.format("notes.txt")),
          harness.view.notices.subList(notices, harness.view.notices.size()));
    } finally {
      release.countDown();
    }
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return renamed.equals(documents().activePath());
          }
        },
        "renamed");
  }

  /** A rename reads the new path's per-file settings, as a Save As to that path does. */
  @Test
  void aRenameReadsThePerFileSettingsOfTheNewName() throws Exception {
    Path ec =
        Files.writeString(
            root.resolve(".editorconfig"),
            "root = true\n[*.txt]\nindent_size = 7\n[*.c]\nindent_size = 3\n");
    final Path notes = root.resolve("notes.txt");
    harness.open(notes);
    assertEquals(7, documents().activeDocument().tabWidth());
    workspace().files().rename(file(notes));
    answer("notes.c");
    final Path renamed = root.resolve("notes.c");
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return renamed.equals(documents().activePath())
                && documents().activeDocument().tabWidth() == 3;
          }
        },
        "the settings of notes.c");
    String tip = harness.view.selected.toolTip;
    assertTrue(tip.startsWith(renamed + "\n") && tip.contains(ec.toString()), tip);
  }

  /** A save refused while a rename runs leaves the text untrimmed; the next save trims. */
  @Test
  void aSaveRefusedDuringARenameRunsNoCleanup() throws Exception {
    Files.writeString(
        root.resolve(".editorconfig"), "root = true\n[*]\ntrim_trailing_whitespace = true\n");
    final Path trail = Files.writeString(root.resolve("trail.txt"), "a  \n");
    harness.open(trail);
    documents().activeEditor().type("x");
    final CountDownLatch entered = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);
    workspace()
        .files()
        .setBeforeRename(
            new Runnable() {
              @Override
              public void run() {
                entered.countDown();
                try {
                  release.await(Harness.BOUND.toSeconds(), TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
              }
            });
    workspace().files().rename(file(trail));
    answer("trimmed.txt");
    final Path renamed = root.resolve("trimmed.txt");
    try {
      assertTrue(entered.await(Harness.BOUND.toSeconds(), TimeUnit.SECONDS), "rename started");
      documents().save();
      awaitOnlyTheRename("save refused");
      assertTrue(lastInfo().contains(UiText.RENAME_RUNNING.format("trail.txt")), lastInfo());
      assertEquals("xa  \n", documents().activeDocument().text().toString());
      assertEquals("a  \n", Files.readString(trail));
    } finally {
      release.countDown();
    }
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return renamed.equals(documents().activePath());
          }
        },
        "renamed");
    documents().save();
    harness.awaitIdle("save");
    assertEquals("xa\n", Files.readString(renamed));
  }

  @Test
  void aRenameMovesTheRecoveryFileToTheNewPath() throws Exception {
    final Path directory = startRecovery();
    final Path notes = root.resolve("notes.txt");
    harness.open(notes);
    documents().activeEditor().type("x ");
    harness.ui.runLongTimers();
    awaitHeader(directory, notes);
    workspace().files().rename(file(notes));
    answer("renamed.txt");
    final Path renamed = root.resolve("renamed.txt");
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return renamed.equals(documents().activePath());
          }
        },
        "renamed");
    harness.ui.runLongTimers();
    awaitHeader(directory, renamed);
  }

  private void awaitHeader(final Path directory, final Path expected) throws InterruptedException {
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            try {
              return expected.toString().equals(recoveryPath(directory));
            } catch (IOException e) {
              return false;
            }
          }
        },
        "recovery file names " + expected.getFileName());
  }

  private static String recoveryPath(Path root) throws IOException {
    if (!Files.isDirectory(root)) {
      return null;
    }
    try (DirectoryStream<Path> directories = Files.newDirectoryStream(root)) {
      for (Path directory : directories) {
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "*.qcr")) {
          for (Path file : files) {
            return RecoveryFile.load(file, LoadReservation.UNMETERED).header().path();
          }
        }
      }
    }
    return null;
  }
}
