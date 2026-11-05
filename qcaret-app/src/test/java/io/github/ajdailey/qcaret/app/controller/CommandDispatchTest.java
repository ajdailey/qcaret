package io.github.ajdailey.qcaret.app.controller;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.view.UnsavedChoice;
import io.github.ajdailey.qcaret.core.document.Selection;
import io.github.ajdailey.qcaret.core.document.SelectionSet;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runs every {@link CommandId} through {@link ApplicationController#execute} with no document and
 * with a document holding a selection. No command throws on the UI thread or on a worker, and none
 * leaves an operation counted active. {@code QUIT} runs last, as the shutdown.
 */
class CommandDispatchTest {
  @TempDir Path temp;

  private static final String TEXT = "line 0\r\nline 1\nline 2\nline 3\r\nline 4\nline 5\nline 6\n";

  private Harness harness;
  private Path file;

  @BeforeEach
  void create() throws Exception {
    harness = new Harness(temp);
    file = Files.writeString(temp.resolve("dispatch.txt"), TEXT);
    harness.view.clipboard = "pasted";
  }

  @AfterEach
  void shutDown() throws InterruptedException {
    harness.application.execute(CommandId.QUIT);
    discardPrompts();
    harness.close();
  }

  /** The commands this test runs: all but {@code QUIT}. */
  private static List<CommandId> commands() {
    List<CommandId> all = new ArrayList<CommandId>();
    for (CommandId command : CommandId.values()) {
      if (command != CommandId.QUIT) {
        all.add(command);
      }
    }
    return all;
  }

  /** Runs a command and the UI queue until no operation is active. */
  private void run(final CommandId command) {
    assertDoesNotThrow(
        new Executable() {
          @Override
          public void execute() throws InterruptedException {
            harness.application.execute(command);
            harness.awaitIdle(command.name());
          }
        },
        command.name());
    assertTrue(harness.failures.isEmpty(), command + ": " + harness.failures);
  }

  /** Answers every unsaved-changes prompt with Don't Save. */
  private void discardPrompts() throws InterruptedException {
    harness.ui.runPending();
    while (harness.view.unsaved != null) {
      Consumer<UnsavedChoice> answer = harness.view.unsaved;
      harness.view.unsaved = null;
      answer.accept(UnsavedChoice.DISCARD);
      harness.ui.runPending();
    }
  }

  /** Opens the file when no document shows, and selects a range in it. */
  private void shown() throws InterruptedException {
    DocumentController documents = harness.application.documents();
    if (documents.activeEditor() == null) {
      harness.open(file);
    }
    if (documents.activeDocument().text().length() < 50) {
      documents.activeEditor().selectAll();
      documents.activeEditor().type(TEXT);
    }
    documents
        .activeEditor()
        .selections()
        .set(SelectionSet.of(List.of(Selection.of(3, 12), Selection.caret(40)), 0));
  }

  private static final String FOLDED = "a {\n  b {\n    c\n  }\n  d\n}\ne\n";

  /** Shows the indented text in the selected view with every region collapsed. */
  private void folded() throws Exception {
    DocumentController documents = harness.application.documents();
    if (documents.activeEditor() == null) {
      harness.open(Files.writeString(temp.resolve("folded.txt"), FOLDED));
    }
    final EditorController editor = documents.activeEditor();
    editor.document().setReadOnly(false);
    if (!editor.document().text().toString().equals(FOLDED)) {
      editor.selectAll();
      editor.type(FOLDED);
    }
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return editor.projection().foldRegionCount() == 2;
          }
        },
        "fold regions");
    editor.projection().foldAll();
    editor.selections().set(SelectionSet.caret(1));
  }

  @Test
  void everyCommandRunsWithCollapsedRegionsInASplitView() throws Exception {
    folded();
    harness.application.execute(CommandId.SPLIT_HORIZONTAL);
    for (CommandId command : commands()) {
      folded();
      run(command);
      if (harness.view.unsaved != null) {
        Consumer<UnsavedChoice> answer = harness.view.unsaved;
        harness.view.unsaved = null;
        answer.accept(UnsavedChoice.CANCEL);
      }
    }
    assertEquals(0, harness.view.active.get());
  }

  @Test
  void everyCommandRunsWithNoDocument() throws InterruptedException {
    for (CommandId command : commands()) {
      run(command);
      harness.application.documents().closeAll(null);
      discardPrompts();
      assertEquals(0, harness.application.documents().count(), command.name());
    }
    assertEquals(0, harness.view.active.get());
  }

  @Test
  void everyCommandRunsOnADocument() throws InterruptedException {
    for (CommandId command : commands()) {
      shown();
      run(command);
      if (harness.view.unsaved != null) {
        Consumer<UnsavedChoice> answer = harness.view.unsaved;
        harness.view.unsaved = null;
        answer.accept(UnsavedChoice.CANCEL);
      }
    }
    assertEquals(0, harness.view.active.get());
  }
}
