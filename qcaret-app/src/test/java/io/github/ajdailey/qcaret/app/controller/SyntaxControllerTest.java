package io.github.ajdailey.qcaret.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.core.document.Selection;
import io.github.ajdailey.qcaret.core.document.SelectionSet;
import io.github.ajdailey.qcaret.core.syntax.GrammarSet;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Grammar detection, the manual choice, user grammars and the comment commands through the
 * application controller.
 */
class SyntaxControllerTest {
  @TempDir Path temp;

  private Harness harness;
  private CommandStates states;

  @BeforeEach
  void create() {
    harness = new Harness(temp);
    states = new CommandStates(harness.application);
  }

  @AfterEach
  void close() throws InterruptedException {
    harness.application.documents().dispose();
    harness.close();
  }

  private void loadGrammars() throws InterruptedException {
    final GrammarLibrary library = harness.application.grammars();
    final Object before = library.set();
    harness.application.loadGrammars();
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return library.set() != null && library.set() != before;
          }
        },
        "grammars read");
  }

  private void awaitLanguage(final String name) throws InterruptedException {
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return states.status().language().equals(name);
          }
        },
        "language " + name + ", was " + states.status().language());
  }

  private void open(String name, String text) throws Exception {
    harness.open(Files.writeString(temp.resolve(name), text, StandardCharsets.UTF_8));
  }

  @Test
  void detectsByNameAndFirstLineAndKeepsAManualChoice() throws Exception {
    loadGrammars();
    open("Mock.java", "class Mock {}\n");
    awaitLanguage("Java");
    assertSame(harness.view.selected.syntax, harness.application.documents().activeFoldRegions());
    open("run", "#!/bin/sh\necho hi\n");
    awaitLanguage("Shell Script");
    open("notes", "plain words\n");
    awaitLanguage("Plain Text");
    harness.application.setSyntax("python");
    awaitLanguage("Python");
    List<CommandStates.SyntaxChoice> choices = states.syntaxChoices();
    assertEquals("plain", choices.get(0).id());
    assertEquals(GrammarSet.BUILT_IN.size(), choices.size());
    int checked = 0;
    for (CommandStates.SyntaxChoice choice : choices) {
      checked += choice.checked() ? 1 : 0;
    }
    assertEquals(1, checked);
    loadGrammars();
    awaitLanguage("Python");
  }

  @Test
  void aUserGrammarReplacesABuiltInAndAnInvalidOneIsSkippedWithANotice() throws Exception {
    Path folder = Files.createDirectories(temp.resolve("settings").resolve("grammars"));
    Files.writeString(
        folder.resolve("java.xml"),
        "<grammar id=\"java\" name=\"Mock Java\"><detect><extension>java</extension></detect>"
            + "<comments line=\"#\"/><context id=\"main\"/></grammar>",
        StandardCharsets.UTF_8);
    Files.writeString(folder.resolve("broken.xml"), "<grammar id=\"x\">", StandardCharsets.UTF_8);
    loadGrammars();
    assertEquals(1, harness.view.notices.size());
    assertTrue(harness.view.notices.get(0).contains("broken.xml"), harness.view.notices.get(0));
    open("Mock.java", "class Mock {}\n");
    awaitLanguage("Mock Java");
    harness.application.execute(CommandId.TOGGLE_LINE_COMMENT);
    assertEquals(
        "# class Mock {}\n", harness.application.documents().activeDocument().text().toString());
    assertFalse(states.enabled(CommandId.TOGGLE_BLOCK_COMMENT));
  }

  @Test
  void commentCommandsActAtEveryCaretAsOneUndoStep() throws Exception {
    loadGrammars();
    open("Mock.java", "int a;\nint b;\nint c;\n");
    awaitLanguage("Java");
    EditorController editor = harness.application.documents().activeEditor();
    editor.selections().set(SelectionSet.of(List.of(Selection.caret(0), Selection.caret(14)), 0));
    assertTrue(states.enabled(CommandId.TOGGLE_LINE_COMMENT));
    harness.application.execute(CommandId.TOGGLE_LINE_COMMENT);
    assertEquals(
        "// int a;\nint b;\n// int c;\n",
        harness.application.documents().activeDocument().text().toString());
    harness.application.execute(CommandId.UNDO);
    assertEquals(
        "int a;\nint b;\nint c;\n",
        harness.application.documents().activeDocument().text().toString());
    editor.selections().set(SelectionSet.of(List.of(Selection.of(4, 5)), 0));
    harness.application.execute(CommandId.TOGGLE_BLOCK_COMMENT);
    assertEquals(
        "int /* a */;\nint b;\nint c;\n",
        harness.application.documents().activeDocument().text().toString());
    harness.application.documents().toggleReadOnly();
    assertFalse(states.enabled(CommandId.TOGGLE_LINE_COMMENT));
  }
}
