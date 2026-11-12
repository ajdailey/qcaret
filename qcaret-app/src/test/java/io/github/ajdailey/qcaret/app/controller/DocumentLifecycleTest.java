package io.github.ajdailey.qcaret.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.view.UnsavedChoice;
import io.github.ajdailey.qcaret.app.view.WorkspaceNode;
import io.github.ajdailey.qcaret.core.document.Document;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * An open document's state across a tab move, a reopen, a rename and a close during its save's
 * cleanup: the per-file settings, the untitled name, the buffer id and the chosen grammar stay with
 * the document, and the window holding it reports.
 */
class DocumentLifecycleTest {
  private static final WindowSet.Placement END = new WindowSet.Placement(Integer.MAX_VALUE, 4, 5);

  @TempDir Path temp;

  private Windows windows;
  private ApplicationController first;
  private Path root;

  @BeforeEach
  void setUp() throws Exception {
    windows = new Windows(temp, false);
    first = windows.first();
    root = Files.createDirectory(temp.resolve("ws")).toRealPath();
  }

  @AfterEach
  void tearDown() throws Exception {
    windows.close();
  }

  private Document open(String name, String text) throws Exception {
    windows.open(first, Files.writeString(root.resolve(name), text, StandardCharsets.UTF_8));
    return first.documents().activeDocument();
  }

  private Path editorConfig(String sections) throws Exception {
    return Files.writeString(root.resolve(".editorconfig"), "root = true\n" + sections);
  }

  private ApplicationController move(Document document) {
    ApplicationController target = windows.set.moveDocument(first, document, null, END);
    assertNotNull(target);
    return target;
  }

  @Test
  void aMovedTabKeepsItsDeclaredSettingsAndSavesWithThem() throws Exception {
    Path ec = editorConfig("[*]\nindent_size = 3\ntrim_trailing_whitespace = true\n");
    Document document = open("a.txt", "a  \n");
    assertEquals(3, document.tabWidth());
    ApplicationController target = move(document);
    FakeView view = windows.view(target);
    assertTrue(view.selected.toolTip.contains(ec.toString()), view.selected.toolTip);
    assertEquals(3, document.tabWidth());
    target.documents().activeEditor().type("x");
    target.documents().save();
    windows.settle("save");
    assertEquals("xa\n", Files.readString(root.resolve("a.txt")));
    assertFalse(document.isDirty());
    assertEquals("a.txt", view.selected.title);
  }

  @Test
  void anUntitledTabKeepsItsNameBufferAndGrammarAcrossAMove() throws Exception {
    final GrammarLibrary library = first.grammars();
    first.loadGrammars();
    windows.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return library.set() != null;
          }
        },
        "grammars read");
    first.documents().newDocument();
    Document document = first.documents().activeDocument();
    first.documents().activeEditor().type("x");
    first.documents().setSyntax("java");
    DocumentController.Buffer before = first.documents().dirtyBuffers().get(0);
    String title = windows.view(first).selected.title;

    ApplicationController target = move(document);

    List<DocumentController.Buffer> moved = target.documents().dirtyBuffers();
    assertEquals(1, moved.size());
    assertEquals(before.id(), moved.get(0).id());
    assertEquals(before.title(), moved.get(0).title());
    assertEquals("java", moved.get(0).language());
    assertEquals(title, windows.view(target).selected.title);
    first.documents().newDocument();
    first.documents().activeEditor().type("y");
    DocumentController.Buffer next = first.documents().dirtyBuffers().get(0);
    assertNotEquals(before.id(), next.id());
    assertNotEquals(before.title(), next.title());
  }

  @Test
  void aReopenInTheTargetWindowReplacesTheMovedDocument() throws Exception {
    Document document = open("re.txt", "café\n");
    ApplicationController target = move(document);
    target.documents().reopenWithEncoding(StandardCharsets.ISO_8859_1);
    windows.settle("reopen");
    assertEquals(0, first.documents().count());
    assertEquals(1, target.documents().count());
    Document reopened = target.documents().activeDocument();
    assertEquals(StandardCharsets.ISO_8859_1, reopened.encoding().charset());
    assertEquals("cafÃ©\n", reopened.text().toString());
    assertEquals("re.txt", windows.view(target).selected.title);
    assertTrue(target.documents().canMove(reopened), "the reopen still holds the tab");
  }

  /** A rename from one window's browser holds and moves a document another window shows. */
  @Test
  void aRenameHoldsAndMovesADocumentInAnotherWindowAndReadsItsSettings() throws Exception {
    Path ec = editorConfig("[*.txt]\nindent_size = 7\n[*.c]\nindent_size = 3\n");
    final Path notes = Files.writeString(root.resolve("notes.txt"), "notes\n");
    final WorkspaceController workspace = first.workspace();
    workspace.set(root);
    windows.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return root.equals(workspace.root()) && workspace.index() != null;
          }
        },
        "workspace set and indexed");
    first.execute(CommandId.WORKSPACE_BROWSER);
    windows.open(first, notes);
    final Document document = first.documents().activeDocument();
    assertEquals(7, document.tabWidth());
    final ApplicationController target = move(document);
    target.documents().activeEditor().type("x ");
    final CountDownLatch entered = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);
    workspace
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
    workspace.files().rename(new WorkspaceNode(notes, "notes.txt", WorkspaceNode.Kind.FILE));
    FakeView source = windows.view(first);
    assertNotNull(source.fileNameAnswer, "asked for a name");
    source.fileNameAnswer.accept("notes.c");
    final Path renamed = root.resolve("notes.c");
    FakeView view = windows.view(target);
    try {
      assertTrue(entered.await(Harness.BOUND.toSeconds(), TimeUnit.SECONDS), "rename started");
      int infos = view.infos.size();
      target.documents().save();
      windows.settle("save refused");
      assertEquals(infos + 1, view.infos.size(), view.infos.toString());
      String info = view.infos.get(infos);
      assertTrue(info.contains(UiText.RENAME_RUNNING.format("notes.txt")), info);
      assertEquals("notes\n", Files.readString(notes));
    } finally {
      release.countDown();
    }
    windows.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return renamed.equals(target.documents().activePath()) && document.tabWidth() == 3;
          }
        },
        "the settings of notes.c");
    assertEquals("*notes.c", view.selected.title);
    assertTrue(view.selected.toolTip.contains(ec.toString()), view.selected.toolTip);
    target.documents().save();
    windows.settle("save");
    assertEquals("x notes\n", Files.readString(renamed));
  }

  /** A window whose tab moves out during a rename holds nothing once the rename lands. */
  @Test
  void aTabMovedOutDuringARenameLeavesNoHoldInTheWindowItLeft() throws Exception {
    final Path notes = Files.writeString(root.resolve("notes.txt"), "notes\n");
    final WorkspaceController workspace = first.workspace();
    workspace.set(root);
    windows.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return root.equals(workspace.root()) && workspace.index() != null;
          }
        },
        "workspace set and indexed");
    first.execute(CommandId.WORKSPACE_BROWSER);
    windows.open(first, notes);
    final Document document = first.documents().activeDocument();
    final ApplicationController target = move(document);
    final CountDownLatch entered = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);
    workspace
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
    workspace.files().rename(new WorkspaceNode(notes, "notes.txt", WorkspaceNode.Kind.FILE));
    windows.view(first).fileNameAnswer.accept("notes.c");
    final Path renamed = root.resolve("notes.c");
    try {
      assertTrue(entered.await(Harness.BOUND.toSeconds(), TimeUnit.SECONDS), "rename started");
      assertNotNull(windows.set.moveDocument(target, document, first, END), "the tab moves back");
    } finally {
      release.countDown();
    }
    windows.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return renamed.equals(first.documents().pathOf(document));
          }
        },
        "the rename landed");
    target.documents().newDocument();
    target.documents().activeEditor().type("y");
    final FakeView view = windows.view(target);
    final int infos = view.infos.size();
    view.saveChooser = null;
    target.documents().saveAs();
    assertNotNull(view.saveChooser, "Save As asks for a file");
    view.saveChooser.accept(notes);
    windows.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return Files.exists(notes) || view.infos.size() > infos;
          }
        },
        "the Save As wrote or reported");
    windows.settle("save as");
    assertEquals(infos, view.infos.size(), view.infos.toString());
    assertEquals("y", Files.readString(notes, StandardCharsets.UTF_8));
  }

  @Test
  void closingTheTabDuringItsSaveCleanupWritesNothing() throws Exception {
    editorConfig("[*]\ntrim_trailing_whitespace = true\n");
    Document document = open("c.txt", "a  \n");
    first.documents().activeEditor().type("x");
    first.documents().save();
    first.documents().closeActive();
    FakeView view = windows.view(first);
    assertNotNull(view.unsaved, "asked about the unsaved changes");
    view.unsaved.accept(UnsavedChoice.DISCARD);
    windows.settle("the cleanup");
    assertEquals("a  \n", Files.readString(root.resolve("c.txt")));
    assertFalse(first.documents().isOpen(document));
    assertEquals("xa  \n", document.text().toString());
  }
}
