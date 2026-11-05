package io.github.ajdailey.qcaret.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.view.ChangeChoice;
import io.github.ajdailey.qcaret.app.view.Comparison;
import io.github.ajdailey.qcaret.app.view.ManualUiDispatcher;
import io.github.ajdailey.qcaret.core.buffer.TextSnapshot;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.document.Document;
import io.github.ajdailey.qcaret.core.document.LoadOptions;
import io.github.ajdailey.qcaret.core.testkit.Poll;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * External change detection against a fake view: reload of a clean document, the dirty-buffer
 * prompt, missing files, the save conflict and the comparisons. Each check runs on window
 * activation ({@link DocumentController#checkFiles}); the waits poll the UI queue with a bound and
 * never assert a state the checked side effect has not produced yet.
 */
class ExternalChangeTest {
  private static final Duration BOUND = Duration.ofSeconds(20);

  @TempDir Path temp;

  private final ManualUiDispatcher ui = new ManualUiDispatcher();
  private final FakeView view = new FakeView(ui);
  private final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
  private final MemoryBudget budget = new MemoryBudget(1L << 30, 50);
  private Workers workers;
  private DocumentController documents;
  private CompareController compare;

  @BeforeEach
  void setUp() {
    workers =
        new Workers(
            2,
            new Thread.UncaughtExceptionHandler() {
              @Override
              public void uncaughtException(Thread thread, Throwable error) {
                failure.set(error);
              }
            });
    documents = new DocumentController(workers, ui, view, budget, LoadOptions.DEFAULTS);
    compare = new CompareController(documents, workers, ui, view, budget);
    documents.setCompare(compare);
  }

  @AfterEach
  void tearDown() throws Exception {
    compare.dispose();
    documents.dispose();
    assertTrue(
        Poll.until(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return !documents.watching();
              }
            },
            BOUND));
    workers.close();
    assertNull(failure.get());
    assertEquals(0, budget.reserved());
  }

  private void await(BooleanSupplier condition, String what) throws Exception {
    assertTrue(ui.runUntil(condition, BOUND), what);
  }

  /** Waits until no load, save, reload or comparison is active and runs what they posted. */
  private void idle() throws Exception {
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return view.active.get() == 0 && documents.loading() == 0;
          }
        },
        "idle");
    ui.runPending();
  }

  private Path write(String name, String text) throws IOException {
    return Files.writeString(temp.resolve(name), text, StandardCharsets.UTF_8);
  }

  private Document open(Path file) throws Exception {
    final int before = view.tabs.size();
    documents.open(List.of(file));
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return view.tabs.size() == before + 1;
          }
        },
        "opened");
    idle();
    return documents.activeDocument();
  }

  /** Activates the window and waits until the document holds the text. */
  private void checkUntilText(final Document document, final String text) throws Exception {
    documents.checkFiles();
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return document.text().toString().equals(text);
          }
        },
        "reloaded to " + text);
    idle();
  }

  private void checkUntilPrompts(final int count) throws Exception {
    documents.checkFiles();
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return view.changePrompts.size() == count;
          }
        },
        count + " prompts");
  }

  /**
   * Activates the window twice and waits for the second check of every file, which starts after the
   * first ends: a pass in progress may have read a file before it changed.
   */
  private void checkAndSettle() throws Exception {
    for (int i = 0; i < 2; i++) {
      final long passes = documents.checkPasses();
      documents.checkFiles();
      await(
          new BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
              return documents.checkPasses() > passes;
            }
          },
          "a check of every file");
    }
    idle();
  }

  @Test
  void aCleanDocumentReloadsAsOneUndoStepKeepingTheCaretLine() throws Exception {
    Path file = write("a.txt", "one\ntwo\nthree\n");
    Document document = open(file);
    documents.activeEditor().goTo(2, 2);
    write("a.txt", "zero\none\ntwo\nTHREE\n");
    checkUntilText(document, "zero\none\ntwo\nTHREE\n");
    assertFalse(document.isDirty());
    TextSnapshot text = document.text();
    int caret = documents.activeEditor().selections().selections().primary().caret();
    assertEquals(2, text.lineAt(caret));
    assertEquals(2, caret - text.offsetOfLine(2));
    documents.activeEditor().undo();
    assertEquals("one\ntwo\nthree\n", document.text().toString());
    assertTrue(document.isDirty());
    assertTrue(view.changePrompts.isEmpty());
  }

  @Test
  void aDirtyDocumentAsksAndKeepMineMakesTheSaveAsk() throws Exception {
    Path file = write("a.txt", "one\n");
    Document document = open(file);
    documents.activeEditor().type("mine ");
    write("a.txt", "theirs\n");
    checkUntilPrompts(1);
    assertEquals("a.txt", view.changePrompts.get(0));
    view.changeAnswer.accept(ChangeChoice.KEEP);
    checkAndSettle();
    assertEquals(1, view.changePrompts.size());
    assertEquals("mine one\n", document.text().toString());
    documents.save();
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return view.changePrompts.size() == 2;
          }
        },
        "save conflict");
    assertEquals("save a.txt", view.changePrompts.get(1));
    assertEquals("theirs\n", Files.readString(file));
    view.changeAnswer.accept(ChangeChoice.OVERWRITE);
    idle();
    assertEquals("mine one\n", Files.readString(file));
    assertFalse(document.isDirty());
  }

  @Test
  void reloadOfADirtyDocumentKeepsTheEditsInUndo() throws Exception {
    Path file = write("a.txt", "one\n");
    Document document = open(file);
    documents.activeEditor().type("mine ");
    write("a.txt", "theirs\n");
    checkUntilPrompts(1);
    view.changeAnswer.accept(ChangeChoice.RELOAD);
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return document.text().toString().equals("theirs\n");
          }
        },
        "reloaded");
    idle();
    assertFalse(document.isDirty());
    documents.activeEditor().undo();
    assertEquals("mine one\n", document.text().toString());
  }

  @Test
  void aNewerChangeUpdatesThePendingPrompt() throws Exception {
    Path file = write("a.txt", "one\n");
    Document document = open(file);
    documents.activeEditor().type("mine ");
    write("a.txt", "first change\n");
    checkUntilPrompts(1);
    write("a.txt", "second change, longer\n");
    checkAndSettle();
    assertEquals(1, view.changePrompts.size());
    view.changeAnswer.accept(ChangeChoice.RELOAD);
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return document.text().toString().equals("second change, longer\n");
          }
        },
        "reloaded the newer change");
    idle();
  }

  @Test
  void aDeletedFileStaysOpenMissingAndDirtyUntilItReturns() throws Exception {
    Path file = write("a.txt", "one\n");
    final Document document = open(file);
    Files.delete(file);
    documents.checkFiles();
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return document.isDirty();
          }
        },
        "marked missing");
    assertTrue(view.selected.title.contains("missing"), view.selected.title);
    assertEquals("one\n", document.text().toString());
    write("a.txt", "one\n");
    documents.checkFiles();
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !document.isDirty();
          }
        },
        "the same bytes returned");
    assertEquals("a.txt", view.selected.title);
    assertTrue(view.changePrompts.isEmpty());
  }

  @Test
  void aMissingFileIsWrittenBySaveWithoutAPrompt() throws Exception {
    Path file = write("a.txt", "one\n");
    final Document document = open(file);
    Files.delete(file);
    documents.checkFiles();
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return document.isDirty();
          }
        },
        "marked missing");
    documents.save();
    idle();
    assertEquals("one\n", Files.readString(file));
    assertFalse(document.isDirty());
    assertEquals("a.txt", view.selected.title);
  }

  @Test
  void aTouchWithTheSameBytesNeitherPromptsNorBlocksTheSave() throws Exception {
    Path file = write("a.txt", "one\n");
    Document document = open(file);
    documents.activeEditor().type("x");
    FileTime later = FileTime.fromMillis(Files.getLastModifiedTime(file).toMillis() + 5000);
    Files.setLastModifiedTime(file, later);
    checkAndSettle();
    documents.save();
    idle();
    assertTrue(view.changePrompts.isEmpty(), view.changePrompts.toString());
    assertEquals("xone\n", Files.readString(file));
    assertFalse(document.isDirty());
  }

  @Test
  void anOwnSaveIsNotAnExternalChange() throws Exception {
    Path file = write("a.txt", "one\n");
    Document document = open(file);
    for (int i = 0; i < 5; i++) {
      documents.activeEditor().type("x");
      documents.save();
      documents.checkFiles();
      idle();
    }
    checkAndSettle();
    assertTrue(view.changePrompts.isEmpty(), view.changePrompts.toString());
    assertEquals("xxxxxone\n", document.text().toString());
    assertEquals("xxxxxone\n", Files.readString(file));
  }

  @Test
  void compareFromThePromptShowsTheFileBesideTheBuffer() throws Exception {
    Path file = write("a.txt", "one\ntwo\n");
    open(file);
    documents.activeEditor().type("mine ");
    write("a.txt", "one\nthree\n");
    checkUntilPrompts(1);
    view.changeAnswer.accept(ChangeChoice.COMPARE);
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return view.comparisons.size() == 1;
          }
        },
        "comparison");
    Comparison c = view.comparisons.get(0);
    assertEquals("one\nthree\n", c.leftText());
    assertEquals("mine one\ntwo\n", c.rightText());
    assertEquals(
        List.of(Comparison.Row.CHANGED, Comparison.Row.CHANGED, Comparison.Row.SAME), c.rows());
    checkAndSettle();
    assertEquals(1, view.changePrompts.size());
    view.changeAnswer.accept(ChangeChoice.KEEP);
  }

  @Test
  void compareDocumentsAsksForTheOtherDocument() throws Exception {
    open(write("a.txt", "same\nleft\n"));
    open(write("b.txt", "same\nright\nadded\n"));
    compare.compareDocuments();
    assertEquals(1, view.documentChoices.size());
    view.documentChooser.accept(0);
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return view.comparisons.size() == 1;
          }
        },
        "comparison");
    Comparison c = view.comparisons.get(0);
    assertEquals("b.txt", c.leftTitle());
    assertEquals("a.txt", c.rightTitle());
    assertEquals(
        List.of(
            Comparison.Row.SAME,
            Comparison.Row.CHANGED,
            Comparison.Row.REMOVED,
            Comparison.Row.SAME),
        c.rows());
    assertEquals(List.of(1), c.changes());
  }

  @Test
  void aRetargetedLinkReloadsTheNewTarget() throws Exception {
    Path a = write("a.txt", "first\n");
    Path b = write("b.txt", "second\n");
    Path link = temp.resolve("link.txt");
    try {
      Files.createSymbolicLink(link, a);
    } catch (UnsupportedOperationException | IOException e) {
      Assumptions.abort("symbolic links unavailable: " + e);
    }
    Document document = open(link);
    Files.delete(link);
    Files.createSymbolicLink(link, b);
    checkUntilText(document, "second\n");
    assertEquals(b.toRealPath(), documents.activePath());
    documents.activeEditor().type("x");
    documents.save();
    idle();
    assertEquals("xsecond\n", Files.readString(b));
    assertEquals("first\n", Files.readString(a));
  }

  @Test
  void aFileLargerThanEveryBufferReloads() throws Exception {
    StringBuilder text = new StringBuilder();
    while (text.length() < (3 << 20)) {
      text.append("line ").append(text.length()).append('\n');
    }
    Path file = write("large.txt", text.toString());
    Document document = open(file);
    String changed = text + "last\n";
    write("large.txt", changed);
    checkUntilText(document, changed);
    assertFalse(document.isDirty());
  }
}
