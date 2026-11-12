package io.github.ajdailey.qcaret.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.view.QuickOpenInput;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.document.Document;
import io.github.ajdailey.qcaret.core.document.SelectionSet;
import io.github.ajdailey.qcaret.core.syntax.SymbolScanner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Go to Symbol's per-revision cache: a hit at the same revision, a miss after an edit, a scan that
 * an edit outdated, and the scan and its charge dropped when the document closes.
 */
class SymbolCacheTest {
  private static final String SHOP =
      "class Shop {\n" + "  void open() {\n" + "  }\n" + "  int total() {\n" + "  }\n" + "}\n";

  @TempDir Path temp;

  private Harness harness;
  private Path file;
  private EditorController editor;
  private GrammarLibrary grammars;
  private final MemoryBudget budget = new MemoryBudget(1L << 30, 60);
  private final List<DocumentSymbols.Scan> delivered =
      new CopyOnWriteArrayList<DocumentSymbols.Scan>();
  private final Consumer<DocumentSymbols.Scan> collect =
      new Consumer<DocumentSymbols.Scan>() {
        @Override
        public void accept(DocumentSymbols.Scan scan) {
          delivered.add(scan);
        }
      };

  @BeforeEach
  void open() throws Exception {
    harness = new Harness(temp);
    grammars = harness.application.grammars();
    harness.application.loadGrammars();
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return grammars.set() != null;
          }
        },
        "grammars read");
    file = Files.writeString(Files.createDirectory(temp.resolve("ws")).resolve("Shop.java"), SHOP);
    harness.open(file);
    editor = harness.application.documents().activeEditor();
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return editor.compiledSyntax() != null;
          }
        },
        "grammar detected");
  }

  @AfterEach
  void close() throws InterruptedException {
    harness.close();
  }

  private void awaitDelivered(final int count) throws InterruptedException {
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return delivered.size() >= count;
          }
        },
        count + " scans delivered");
  }

  private static List<String> names(DocumentSymbols.Scan scan) {
    List<String> names = new ArrayList<String>();
    for (SymbolScanner.Symbol s : scan.result().symbols()) {
      names.add(s.name() + "@" + (s.line() + 1));
    }
    return names;
  }

  @Test
  void aRequestAtTheKeptRevisionReturnsTheScan() throws Exception {
    SymbolCache cache = new SymbolCache(harness.workers, harness.ui, budget);
    assertNull(cache.request(editor, grammars, file, collect));
    awaitDelivered(1);
    DocumentSymbols.Scan scan = delivered.get(0);
    assertEquals(List.of("Shop@1", "open@2", "total@4"), names(scan));
    assertSame(scan, cache.request(editor, grammars, file, collect));
    assertEquals(1, delivered.size());
    assertEquals(scan.scan().bytes(), budget.reserved());
  }

  @Test
  void anEditMissesAndTheNextScanListsIt() throws Exception {
    SymbolCache cache = new SymbolCache(harness.workers, harness.ui, budget);
    cache.request(editor, grammars, file, collect);
    awaitDelivered(1);
    editor.selections().set(SelectionSet.caret(editor.document().text().offsetOfLine(3)));
    editor.type("  void added() {}\n");
    assertNull(cache.request(editor, grammars, file, collect));
    awaitDelivered(2);
    DocumentSymbols.Scan scan = delivered.get(1);
    assertEquals(editor.document().revision(), scan.revision());
    assertEquals(List.of("Shop@1", "open@2", "added@4", "total@5"), names(scan));
    assertSame(scan, cache.request(editor, grammars, file, collect));
    assertEquals(scan.scan().bytes(), budget.reserved());
  }

  @Test
  void aScanAnEditOutdatedIsDeliveredAndNotReturnedAsCurrent() throws Exception {
    SymbolCache cache = new SymbolCache(harness.workers, harness.ui, budget);
    cache.request(editor, grammars, file, collect);
    long scanned = editor.document().revision();
    editor.selections().set(SelectionSet.caret(0));
    editor.type("class Added {}\n");
    assertNull(cache.request(editor, grammars, file, collect));
    awaitDelivered(2);
    assertEquals(scanned, delivered.get(0).revision());
    assertSame(delivered.get(0), delivered.get(1));
    assertNull(cache.request(editor, grammars, file, collect));
    awaitDelivered(3);
    DocumentSymbols.Scan current = delivered.get(2);
    assertEquals(editor.document().revision(), current.revision());
    assertEquals("Added@1", names(current).get(0));
  }

  @Test
  void closingTheDocumentDropsTheScanAndItsCharge() throws Exception {
    SymbolCache cache = new SymbolCache(harness.workers, harness.ui, budget);
    cache.request(editor, grammars, file, collect);
    awaitDelivered(1);
    assertTrue(budget.reserved() > 0);
    Document document = editor.document();
    cache.documentClosed(document);
    assertNull(cache.kept(document));
    assertEquals(0, budget.reserved());

    final SymbolCache window = harness.application.documents().symbolCache();
    harness.application.execute(CommandId.GO_TO_SYMBOL);
    QuickOpenInput input = harness.view.quickOpen;
    input.close();
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return window.kept(document) != null;
          }
        },
        "a closed list's scan kept");
    harness.application.documents().closeActive();
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            // Closing the last tab opens a fresh Untitled in its place.
            return harness.application.documents().count() == 1
                && harness.application.documents().activeDocument() != document;
          }
        },
        "document closed");
    assertNull(window.kept(document));
  }

  @Test
  void goToSymbolReopenedAtTheSameRevisionListsWithoutAScan() throws Exception {
    harness.application.execute(CommandId.GO_TO_SYMBOL);
    final QuickOpenInput first = harness.view.quickOpen;
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !first.rows().isEmpty();
          }
        },
        "symbols listed");
    first.close();
    harness.application.execute(CommandId.GO_TO_SYMBOL);
    QuickOpenInput again = harness.view.quickOpen;
    assertEquals(3, again.rows().size());
    assertEquals("3 of 3 symbols in Shop.java", again.summary());
    again.close();

    editor.selections().set(SelectionSet.caret(0));
    editor.type("class Added {}\n");
    harness.application.execute(CommandId.GO_TO_SYMBOL);
    final QuickOpenInput edited = harness.view.quickOpen;
    assertTrue(edited.rows().isEmpty());
    assertFalse(edited.summary().startsWith("3 of 3"));
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return edited.rows().size() == 4;
          }
        },
        "the edit listed, summary " + edited.summary());
    assertNotNull(harness.application.documents().symbolCache().kept(editor.document()));
    edited.close();
  }
}
