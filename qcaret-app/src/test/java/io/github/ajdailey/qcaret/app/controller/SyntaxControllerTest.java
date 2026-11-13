package io.github.ajdailey.qcaret.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.session.Session;
import io.github.ajdailey.qcaret.core.document.Selection;
import io.github.ajdailey.qcaret.core.document.SelectionSet;
import io.github.ajdailey.qcaret.core.syntax.GrammarSet;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
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
    SyntaxMenu menu = states.syntaxMenu();
    assertEquals(GrammarSet.PLAIN, menu.plain().id());
    assertEquals("python", menu.recent().get(0).id());
    assertTrue(menu.recent().get(0).checked());
    int count = 1;
    int checked = 0;
    for (SyntaxMenu.Category category : menu.categories()) {
      for (CommandStates.SyntaxChoice choice : category.choices()) {
        count++;
        checked += choice.checked() ? 1 : 0;
      }
    }
    assertEquals(GrammarSet.BUILT_IN.size(), count);
    assertEquals(1, checked);
    loadGrammars();
    awaitLanguage("Python");
  }

  @Test
  void saveAsUnderAnotherNameDetectsTheGrammarAgain() throws Exception {
    loadGrammars();
    open("notes", "class Mock {}\n");
    awaitLanguage("Plain Text");
    harness.application.documents().saveAs();
    harness.view.saveChooser.accept(temp.resolve("Mock.java"));
    awaitLanguage("Java");
  }

  private static Session.Tab tab(Path file, String language) {
    return new Session.Tab(
        file.toString(),
        "",
        "",
        "",
        language,
        false,
        4,
        false,
        List.of(new Session.View("0,0", 0, "")),
        0,
        false,
        "",
        "",
        List.of(),
        false);
  }

  private void restore(Path file, String language) throws InterruptedException {
    final DocumentController documents = harness.application.documents();
    final int before = documents.count();
    documents.restore(
        tab(file, language),
        new Consumer<String>() {
          @Override
          public void accept(String name) {
            throw new AssertionError("not restored: " + name);
          }
        },
        new Runnable() {
          @Override
          public void run() {
            // The count shows the tab.
          }
        });
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return documents.count() == before + 1;
          }
        },
        "restored " + file.getFileName());
    harness.awaitIdle("restore");
  }

  @Test
  void aSessionGrammarAppliesAndOneTheLibraryLacksDetects() throws Exception {
    loadGrammars();
    restore(Files.writeString(temp.resolve("notes"), "plain words\n"), "python");
    awaitLanguage("Python");
    restore(Files.writeString(temp.resolve("Mock.java"), "class Mock {}\n"), "mock-missing");
    awaitLanguage("Java");
    List<Session.Tab> tabs = harness.application.documents().sessionTabs(false);
    assertEquals("python", tabs.get(0).language());
    assertEquals("", tabs.get(1).language());
  }

  @Test
  void aClosedFileKeepsItsChosenGrammarForTheNextOpen() throws Exception {
    final Map<String, Session.FileState> kept = new HashMap<String, Session.FileState>();
    DocumentController documents = harness.application.documents();
    documents.setFileMemory(
        new DocumentController.FileMemory() {
          @Override
          public Session.FileState state(Path path) {
            return kept.get(path.toString());
          }

          @Override
          public void remember(Session.FileState state) {
            kept.put(state.path(), state);
          }
        });
    loadGrammars();
    open("other.txt", "other\n");
    open("notes", "plain words\n");
    awaitLanguage("Plain Text");
    harness.application.setSyntax("python");
    documents.closeActive();
    harness.awaitIdle("close");
    assertEquals(1, kept.size());
    assertEquals("python", kept.values().iterator().next().language());
    documents.reopenClosed();
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return documents.count() == 2;
          }
        },
        "reopened");
    awaitLanguage("Python");
  }

  @Test
  void theSyntaxMenuKeepsTheLastFiveGrammarsChosen() throws Exception {
    loadGrammars();
    open("notes", "plain words\n");
    awaitLanguage("Plain Text");
    for (String id : new String[] {"java", "go", "ruby", "perl", "lua", "rust", "go", "plain"}) {
      harness.application.setSyntax(id);
    }
    List<String> recent = new ArrayList<String>();
    for (CommandStates.SyntaxChoice choice : states.syntaxMenu().recent()) {
      recent.add(choice.id());
    }
    assertEquals(List.of("plain", "go", "rust", "lua", "perl"), recent);
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
