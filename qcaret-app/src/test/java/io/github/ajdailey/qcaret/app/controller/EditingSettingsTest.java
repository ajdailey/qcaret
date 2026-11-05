package io.github.ajdailey.qcaret.app.controller;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.core.document.SelectionSet;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Save All, Reopen Closed Tab, read-only, tab settings and Go to Line through the window. */
class EditingSettingsTest {
  @TempDir Path temp;

  private Harness harness;
  private DocumentController documents;

  @BeforeEach
  void create() {
    harness = new Harness(temp);
    documents = harness.application.documents();
  }

  @AfterEach
  void close() throws InterruptedException {
    documents.dispose();
    harness.close();
  }

  private Path file(String name, String text) throws Exception {
    return Files.writeString(temp.resolve(name), text, StandardCharsets.UTF_8);
  }

  @Test
  void saveAllWritesEveryChangedDocument() throws Exception {
    final Path a = file("a.txt", "a");
    final Path b = file("b.txt", "b");
    harness.open(a);
    documents.activeEditor().type("1");
    harness.open(b);
    documents.activeEditor().type("2");
    assertTrue(new CommandStates(harness.application).enabled(CommandId.SAVE_ALL));
    harness.application.execute(CommandId.SAVE_ALL);
    final CommandStates states = new CommandStates(harness.application);
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !states.enabled(CommandId.SAVE_ALL);
          }
        },
        "both saved");
    harness.awaitIdle("save all");
    assertEquals("1a", Files.readString(a));
    assertEquals("2b", Files.readString(b));
  }

  @Test
  void reopenClosedOpensTheLastClosedFileAgain() throws Exception {
    harness.open(file("first.txt", "1"));
    harness.open(file("second.txt", "2"));
    CommandStates states = new CommandStates(harness.application);
    assertFalse(states.enabled(CommandId.REOPEN_CLOSED));
    documents.closeAll(null);
    assertEquals(0, documents.count());
    assertTrue(states.enabled(CommandId.REOPEN_CLOSED));
    harness.application.execute(CommandId.REOPEN_CLOSED);
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return documents.count() == 1;
          }
        },
        "reopened");
    assertEquals("second.txt", documents.activePath().getFileName().toString());
  }

  @Test
  void readOnlyAndTabSettingsArePerDocument() throws Exception {
    harness.open(file("r.txt", "x\ty"));
    harness.application.execute(CommandId.READ_ONLY);
    assertTrue(documents.activeDocument().isReadOnly());
    assertTrue(harness.view.selected.title.contains("read-only"), harness.view.selected.title);
    documents.activeEditor().type("z");
    assertEquals("x\ty", documents.activeDocument().text().toString());
    harness.application.execute(CommandId.READ_ONLY);
    assertFalse(documents.activeDocument().isReadOnly());
    harness.application.execute(CommandId.TAB_WIDTH_8);
    assertEquals(8, documents.activeDocument().tabWidth());
    assertEquals(8, harness.view.selected.tabWidth);
    assertTrue(new CommandStates(harness.application).checked(CommandId.TAB_WIDTH_8));
    harness.application.execute(CommandId.INDENT_WITH_SPACES);
    documents.activeEditor().selections().set(SelectionSet.caret(1));
    harness.application.execute(CommandId.INSERT_TAB);
    assertEquals("x       \ty", documents.activeDocument().text().toString());
  }

  @Test
  void goToLineTakesALineAndAnOptionalColumn() throws Exception {
    harness.open(file("g.txt", "one\ntwo\nthree"));
    harness.application.execute(CommandId.GO_TO_LINE);
    assertEquals(3, harness.view.goToLines);
    harness.view.goTo.accept(" 3:2 ");
    assertEquals(SelectionSet.caret(9), documents.activeEditor().selections().selections());
    harness.application.execute(CommandId.GO_TO_LINE);
    harness.view.goTo.accept("x");
    assertEquals("Not a line number: x", harness.view.notices.get(harness.view.notices.size() - 1));
    assertArrayEquals(new int[] {2, 1}, DocumentController.parsePosition("2"));
    assertArrayEquals(new int[] {4, 7}, DocumentController.parsePosition("4,7"));
    assertEquals(0, DocumentController.parsePosition("0").length);
    assertEquals(0, DocumentController.parsePosition("1:2:3").length);
  }
}
