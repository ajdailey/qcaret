package io.github.ajdailey.qcaret.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.view.QuickOpenInput;
import io.github.ajdailey.qcaret.app.view.QuickOpenRow;
import io.github.ajdailey.qcaret.core.document.SelectionSet;
import io.github.ajdailey.qcaret.core.syntax.SymbolKind;
import io.github.ajdailey.qcaret.core.syntax.SymbolScanner;
import java.io.IOException;
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
 * Go to Symbol and {@code @} in Quick Open: query parsing, the selected document's symbols, a stale
 * scan dropped, the jump and its history entry, and {@code file@symbol}.
 */
class GoToSymbolTest {
  private static final String SHOP =
      "class Shop {\n"
          + "  // void hidden() {}\n"
          + "  void open() {\n"
          + "  }\n"
          + "  int total(int[] items) {\n"
          + "    return 0;\n"
          + "  }\n"
          + "}\n";

  @TempDir Path temp;

  private Harness harness;
  private Path root;

  @BeforeEach
  void create() throws Exception {
    harness = new Harness(temp);
    root = Files.createDirectory(temp.resolve("ws")).toRealPath();
    final GrammarLibrary library = harness.application.grammars();
    harness.application.loadGrammars();
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return library.set() != null;
          }
        },
        "grammars read");
  }

  @AfterEach
  void close() throws InterruptedException {
    harness.close();
  }

  private Path write(String name, String text) throws IOException {
    return Files.writeString(root.resolve(name), text);
  }

  private EditorController openJava(Path file) throws InterruptedException {
    harness.open(file);
    final EditorController editor = harness.application.documents().activeEditor();
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return editor.compiledSyntax() != null;
          }
        },
        "grammar detected");
    return editor;
  }

  private QuickOpenInput goToSymbol() {
    harness.application.execute(CommandId.GO_TO_SYMBOL);
    return harness.view.quickOpen;
  }

  private void awaitRows(final QuickOpenInput input) throws InterruptedException {
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !input.rows().isEmpty();
          }
        },
        "symbols listed, summary " + input.summary());
  }

  private static List<String> rows(QuickOpenInput input) {
    List<String> rows = new ArrayList<String>();
    for (QuickOpenRow row : input.rows()) {
      rows.add(row.name() + " " + row.detail());
    }
    return rows;
  }

  private static int caret(EditorController editor) {
    return editor.selections().selections().primary().caret();
  }

  @Test
  void anAtSplitsTheFileFromTheSymbol() {
    assertEquals(new QuickOpen.Query("", 0, 0, "run"), QuickOpen.Query.parse(" @ run "));
    assertEquals(new QuickOpen.Query("", 0, 0, ""), QuickOpen.Query.parse("@"));
    assertEquals(
        new QuickOpen.Query("src/Shop.java", 0, 0, "to:1"),
        QuickOpen.Query.parse("src\\Shop.java@to:1"));
    assertEquals(new QuickOpen.Query("a", 0, 0, "b@c"), QuickOpen.Query.parse("a@b@c"));
    assertNull(QuickOpen.Query.parse("Shop.java:3").symbol());
  }

  @Test
  void goToSymbolListsTheDocumentsSymbolsAndJumpsToOne() throws Exception {
    EditorController editor = openJava(write("Shop.java", SHOP));
    QuickOpenInput input = goToSymbol();
    assertEquals("@", input.initialQuery());
    awaitRows(input);
    assertEquals(
        List.of("Shop class, line 1", "open method, line 3", "total method, line 5"), rows(input));
    assertEquals("3 of 3 symbols in Shop.java", input.summary());
    input.filter("@tot");
    assertEquals(List.of("total method, line 5"), rows(input));
    assertFalse(harness.application.history().canBack());
    input.close();
    input.choose(0);
    assertEquals(editor.document().text().offsetOfLine(4) + 6, caret(editor));
    assertTrue(harness.application.history().canBack());
  }

  @Test
  void aScanOfAnOlderRevisionIsDroppedAndRunAgain() throws Exception {
    EditorController editor = openJava(write("Shop.java", SHOP));
    QuickOpenInput input = goToSymbol();
    editor.selections().set(SelectionSet.caret(0));
    editor.type("class Added {}\n");
    awaitRows(input);
    assertEquals("Added class, line 1", rows(input).get(0));
    assertEquals(4, input.rows().size());
    input.close();
  }

  @Test
  void plainTextAndAGrammarWithoutRulesSaySo() throws Exception {
    harness.open(write("notes.txt", "class Mock {}\n"));
    final QuickOpenInput input = goToSymbol();
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return input.summary().contains("plain text");
          }
        },
        "plain text summary, was " + input.summary());
    assertTrue(input.rows().isEmpty());
    input.close();
    harness.open(write("data.json", "{\"class\": 1}\n"));
    final QuickOpenInput json = goToSymbol();
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return json.summary().equals("data.json: the JSON grammar defines no symbols");
          }
        },
        "no rules summary, was " + json.summary());
    json.close();
  }

  @Test
  void fileAtSymbolOpensTheFileAtTheBestMatch() throws Exception {
    final Path file = write("Shop.java", SHOP);
    final WorkspaceController workspace = harness.application.workspace();
    workspace.set(root);
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return workspace.index() != null;
          }
        },
        "indexed");
    harness.application.execute(CommandId.QUICK_OPEN);
    QuickOpenInput input = harness.view.quickOpen;
    input.filter("shop@tot");
    assertEquals("Shop.java", input.rows().get(0).name());
    input.close();
    input.choose(0);
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            EditorController editor = harness.application.documents().activeEditor();
            return editor != null
                && file.equals(harness.application.documents().activePath())
                && caret(editor) == editor.document().text().offsetOfLine(4) + 6;
          }
        },
        "caret at total");

    harness.application.execute(CommandId.QUICK_OPEN);
    QuickOpenInput missing = harness.view.quickOpen;
    missing.filter("shop@zzz");
    missing.close();
    missing.choose(0);
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return harness.view.notices.contains("No symbol in Shop.java matches zzz");
          }
        },
        "notice, was " + harness.view.notices);
  }

  @Test
  void theBestMatchIsTheFirstOfTheTopScore() {
    List<SymbolScanner.Symbol> symbols =
        List.of(
            new SymbolScanner.Symbol("rerun", SymbolKind.FUNCTION, 0, 0),
            new SymbolScanner.Symbol("run", SymbolKind.FUNCTION, 1, 0),
            new SymbolScanner.Symbol("run", SymbolKind.METHOD, 2, 0));
    assertEquals(1, SymbolRows.best(symbols, "run").line());
    assertNull(SymbolRows.best(symbols, "xyz"));
  }
}
