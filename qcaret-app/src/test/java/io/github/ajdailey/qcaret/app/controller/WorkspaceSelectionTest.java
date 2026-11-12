package io.github.ajdailey.qcaret.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.view.WorkspaceNode;
import io.github.ajdailey.qcaret.app.view.WorkspaceSelectionInput;
import io.github.ajdailey.qcaret.app.view.WorkspaceSelectionInput.Action;
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
 * The workspace browser's row menu over several selected rows: which actions apply, Open, Open in
 * Default Application and Copy Path on every selected path, the focused row's New File and Set as
 * Workspace Folder, and one prompt deleting several entries.
 */
class WorkspaceSelectionTest {
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
    Files.writeString(root.resolve("todo.txt"), "todo\n");
    final WorkspaceController workspace = harness.application.workspace();
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

  private WorkspaceSelectionInput selection() {
    return harness.application.workspace().selection();
  }

  private WorkspaceNode file(String relative) {
    Path path = root.resolve(relative);
    return new WorkspaceNode(path, path.getFileName().toString(), WorkspaceNode.Kind.FILE);
  }

  private WorkspaceNode folder(String relative) {
    Path path = root.resolve(relative);
    return new WorkspaceNode(path, path.getFileName().toString(), WorkspaceNode.Kind.DIRECTORY);
  }

  private void awaitAsked() throws InterruptedException {
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return harness.view.deleteAsked != null;
          }
        },
        "asked");
  }

  @Test
  void severalRowsTakeOpenCopyAndDeleteButNotSearchOrRename() {
    List<WorkspaceNode> rows = List.of(folder("src"), file("notes.txt"));
    for (Action action :
        List.of(
            Action.OPEN,
            Action.OPEN_DEFAULT,
            Action.COPY_PATH,
            Action.DELETE,
            Action.NEW_FILE,
            Action.NEW_FOLDER,
            Action.SET_FOLDER)) {
      assertTrue(selection().enabled(action, rows, null), action.toString());
    }
    assertFalse(selection().enabled(Action.SEARCH_FOLDER, rows, null));
    assertFalse(selection().enabled(Action.RENAME, rows, null));
    assertTrue(selection().enabled(Action.SEARCH_FOLDER, List.of(folder("src")), null));
    assertTrue(selection().enabled(Action.RENAME, List.of(file("notes.txt")), null));

    WorkspaceNode notes = file("notes.txt");
    assertFalse(selection().enabled(Action.NEW_FILE, rows, notes), "focused file");
    assertFalse(selection().enabled(Action.SET_FOLDER, rows, notes), "focused file");
    List<WorkspaceNode> folders = List.of(folder("src"), folder("src/deep"));
    assertFalse(selection().enabled(Action.OPEN, folders, null));
    assertFalse(selection().enabled(Action.OPEN_DEFAULT, folders, null));
  }

  @Test
  void openTakesEverySelectedFileAndLeavesFoldersOut() throws Exception {
    selection().run(Action.OPEN, List.of(file("notes.txt"), folder("src"), file("todo.txt")), null);
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return harness.application.documents().count() == 2;
          }
        },
        "two files opened");
    List<String> titles = new ArrayList<String>();
    for (FakeView.FakeTab tab : harness.view.tabs) {
      titles.add(tab.title);
    }
    titles.sort(null);
    assertEquals(List.of("notes.txt", "todo.txt"), titles);
  }

  @Test
  void openInDefaultApplicationLaunchesEverySelectedFile() throws Exception {
    // Decide by extension on every host: Files.isExecutable is true for any file on Windows.
    harness.view.windows = true;
    selection()
        .run(
            Action.OPEN_DEFAULT, List.of(file("notes.txt"), folder("src"), file("todo.txt")), null);
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return harness.view.launched.size() == 2;
          }
        },
        "two launched");
    assertTrue(harness.view.launched.contains(root.resolve("notes.txt").toString()));
    assertTrue(harness.view.launched.contains(root.resolve("todo.txt").toString()));
  }

  @Test
  void openInDefaultApplicationAsksFirstAboveTenFiles() throws Exception {
    // Decide by extension on every host: Files.isExecutable is true for any file on Windows.
    harness.view.windows = true;
    final List<WorkspaceNode> rows = new ArrayList<WorkspaceNode>();
    for (int i = 0; i < WorkspaceSelection.OPEN_UNASKED; i++) {
      String name = "f" + i + ".txt";
      Files.writeString(root.resolve(name), "x\n");
      rows.add(file(name));
    }
    selection().run(Action.OPEN_DEFAULT, rows, null);
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return harness.view.launched.size() == WorkspaceSelection.OPEN_UNASKED;
          }
        },
        "ten launched without asking");
    assertNull(harness.view.openManyAccept);

    harness.view.launched.clear();
    rows.add(file("notes.txt"));
    selection().run(Action.OPEN_DEFAULT, rows, null);
    assertEquals(rows.size(), harness.view.openManyAsked);
    harness.ui.runPending();
    assertTrue(harness.view.launched.isEmpty(), "nothing opens before the answer");
    harness.view.openManyAccept.run();
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return harness.view.launched.size() == rows.size();
          }
        },
        "eleven launched");
  }

  @Test
  void theDeletePromptCountsFolderContentsWithoutZeroParts() {
    assertNull(SelectionDelete.contents(0, 0));
    assertEquals(
        UiText.count(1, UiText.COUNT_FILE, UiText.COUNT_FILES), SelectionDelete.contents(1, 0));
    assertEquals(
        UiText.count(2, UiText.COUNT_FOLDER, UiText.COUNT_FOLDERS), SelectionDelete.contents(0, 2));
    assertEquals(
        UiText.COUNT_AND.format(
            UiText.count(3, UiText.COUNT_FILE, UiText.COUNT_FILES),
            UiText.count(1, UiText.COUNT_FOLDER, UiText.COUNT_FOLDERS)),
        SelectionDelete.contents(3, 1));
    assertEquals("3 files and 1 folder", SelectionDelete.contents(3, 1));
  }

  @Test
  void theDeletePromptSumsSeveralFolders() throws Exception {
    selection()
        .run(Action.DELETE, List.of(folder("src"), folder("src/deep"), file("notes.txt")), null);
    awaitAsked();
    assertEquals(
        UiText.DELETE_MANY_MESSAGE.format(2)
            + "\n\nsrc, notes.txt\n\n"
            + UiText.DELETE_MANY_ONE_FOLDER.format("src", "2 files and 1 folder"),
        harness.view.deleteAsked);
    harness.view.deleteAsked = null;
    Files.createDirectories(root.resolve("empty"));
    Files.createDirectories(root.resolve("more").resolve("inner"));
    selection().run(Action.DELETE, List.of(folder("empty"), folder("more"), folder("src")), null);
    awaitAsked();
    assertEquals(
        UiText.DELETE_MANY_MESSAGE.format(3)
            + "\n\nempty, more, src\n\n"
            + UiText.DELETE_MANY_FOLDERS.format(3, "2 files and 2 folders"),
        harness.view.deleteAsked);
  }

  @Test
  void copyPathCopiesEverySelectedPathOnePerLine() {
    selection().run(Action.COPY_PATH, List.of(folder("src"), file("notes.txt")), null);
    assertEquals(
        root.resolve("src") + System.lineSeparator() + root.resolve("notes.txt"),
        harness.view.clipboard);
  }

  @Test
  void newFileAndSetAsWorkspaceFolderTakeTheFocusedRow() throws Exception {
    List<WorkspaceNode> rows = List.of(folder("src"), folder("src/deep"), file("notes.txt"));
    selection().run(Action.NEW_FILE, rows, folder("src/deep"));
    assertEquals(
        UiText.NEW_FILE_TITLE.text() + ": " + UiText.NEW_ENTRY_MESSAGE.format("deep"),
        harness.view.fileNameAsked);
    harness.view.fileNameAnswer = null;
    final Path deep = root.resolve("src").resolve("deep");
    selection().run(Action.SET_FOLDER, rows, folder("src/deep"));
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return deep.equals(harness.application.workspace().root());
          }
        },
        "deep is the workspace folder");
  }

  @Test
  void deletingSeveralEntriesAsksOnceAndLeavesOutAnEntryUnderAnother() throws Exception {
    harness.open(root.resolve("src").resolve("Main.java"));
    harness.application.documents().activeEditor().type("x ");
    selection()
        .run(Action.DELETE, List.of(folder("src"), file("src/Main.java"), file("notes.txt")), null);
    awaitAsked();
    assertEquals(
        UiText.DELETE_MANY_MESSAGE.format(2)
            + "\n\nsrc, notes.txt\n\n"
            + UiText.DELETE_MANY_ONE_FOLDER.format(
                "src",
                UiText.COUNT_AND.format(
                    UiText.count(2, UiText.COUNT_FILE, UiText.COUNT_FILES),
                    UiText.count(1, UiText.COUNT_FOLDER, UiText.COUNT_FOLDERS)))
            + "\n\n"
            + UiText.DELETE_UNSAVED.format("Main.java"),
        harness.view.deleteAsked);
    assertTrue(Files.exists(root.resolve("notes.txt")), "nothing goes before the answer");
    harness.view.deleteAccept.run();
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !Files.exists(root.resolve("src"))
                && !Files.exists(root.resolve("notes.txt"))
                && UiText.TAB_MISSING.format("*Main.java").equals(harness.view.selected.title);
          }
        },
        "both deleted, document missing");
    assertTrue(Files.exists(root.resolve("todo.txt")));
    assertTrue(harness.view.infos.isEmpty(), harness.view.infos.toString());
  }

  @Test
  void theDeletePromptNamesTheFirstTenEntries() throws Exception {
    List<WorkspaceNode> rows = new ArrayList<WorkspaceNode>();
    List<String> names = new ArrayList<String>();
    for (int i = 0; i < 12; i++) {
      String name = "f" + i + ".txt";
      Files.writeString(root.resolve(name), "x\n");
      rows.add(file(name));
      names.add(name);
    }
    selection().run(Action.DELETE, rows, null);
    awaitAsked();
    assertEquals(
        UiText.DELETE_MANY_MESSAGE.format(12)
            + "\n\n"
            + String.join(", ", names.subList(0, SelectionDelete.LISTED_NAMES))
            + ", +2",
        harness.view.deleteAsked);
  }

  @Test
  void anEmptySelectionStandsForTheShownFolder() {
    assertTrue(selection().enabled(Action.NEW_FOLDER, List.of(), null));
    assertTrue(selection().enabled(Action.COPY_PATH, List.of(), null));
    assertFalse(selection().enabled(Action.DELETE, List.of(), null));
    assertFalse(selection().enabled(Action.RENAME, List.of(), null));
    selection().run(Action.DELETE, List.of(), null);
    harness.ui.runPending();
    assertNull(harness.view.deleteAsked);
  }
}
