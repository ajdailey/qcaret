package io.github.ajdailey.qcaret.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.view.FindListener;
import io.github.ajdailey.qcaret.app.view.FindState;
import io.github.ajdailey.qcaret.core.buffer.RopeStorage;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.document.Document;
import io.github.ajdailey.qcaret.core.document.DocumentChange;
import io.github.ajdailey.qcaret.core.document.DocumentListener;
import io.github.ajdailey.qcaret.core.document.Replacement;
import io.github.ajdailey.qcaret.core.document.SelectionSet;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Replace All bound to its document, and its single-replacement edit above the limit. */
class ReplaceAllRunTest {
  @TempDir Path temp;

  private Harness harness;
  private FindController find;
  private FindState last;
  private final CountDownLatch release = new CountDownLatch(1);

  @BeforeEach
  void create() {
    harness = new Harness(temp);
    find = harness.application.find();
    find.addListener(
        new FindListener() {
          @Override
          public void findStateChanged(FindState state) {
            last = state;
          }

          @Override
          public void focusRequested() {
            // Not asserted.
          }
        });
  }

  @AfterEach
  void close() throws InterruptedException {
    release.countDown();
    if (harness != null) {
      harness.application.documents().dispose();
      harness.close();
    }
  }

  private EditorController open(String name, String text) throws Exception {
    harness.open(Files.writeString(temp.resolve(name), text, StandardCharsets.UTF_8));
    return harness.application.documents().activeEditor();
  }

  private void settle() throws InterruptedException {
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !find.state().running();
          }
        },
        "replace ends");
    harness.ui.runPending();
  }

  /** Holds the next Replace All on its worker until {@link #release} counts down. */
  private void holdWorker() {
    find.setBeforeFind(
        new Runnable() {
          @Override
          public void run() {
            try {
              assertTrue(release.await(Harness.BOUND.toSeconds(), TimeUnit.SECONDS));
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          }
        });
  }

  private void query(String text, String with) throws InterruptedException {
    find.open(true);
    find.setText(text);
    find.setReplacement(with);
    settle();
  }

  @Test
  void aTabSwitchLeavesTheReplaceToLandInItsDocument() throws Exception {
    EditorController first = open("first.txt", "cat cat\n");
    EditorController second = open("second.txt", "cat\n");
    DocumentController documents = harness.application.documents();
    documents.select(0);
    assertSame(first, documents.activeEditor());
    query("cat", "dog");
    holdWorker();
    find.replaceAll();
    documents.select(1);
    assertSame(second, documents.activeEditor());
    assertTrue(find.state().running());
    release.countDown();
    settle();
    assertEquals("dog dog\n", first.document().text().toString());
    assertEquals("cat\n", second.document().text().toString());
    assertEquals("Replaced 2 matches", last.notice());
    first.undo();
    assertEquals("cat cat\n", first.document().text().toString());
  }

  @Test
  void closingItsDocumentCancelsTheReplace() throws Exception {
    EditorController first = open("first.txt", "cat cat\n");
    open("second.txt", "cat\n");
    DocumentController documents = harness.application.documents();
    documents.select(0);
    query("cat", "dog");
    holdWorker();
    find.replaceAll();
    Document closed = first.document();
    final AtomicInteger edits = new AtomicInteger();
    closed.addListener(
        new DocumentListener() {
          @Override
          public void textChanged(DocumentChange change) {
            edits.incrementAndGet();
          }

          @Override
          public void dirtyChanged(boolean dirty) {
            // Not asserted.
          }
        });
    documents.closeActive();
    harness.ui.runPending();
    assertFalse(documents.isOpen(closed));
    assertFalse(find.state().running());
    release.countDown();
    String notice = last.notice();
    harness.application.documents().dispose();
    harness.close();
    harness.ui.runPending();
    harness = null;
    assertEquals(0, edits.get());
    assertFalse(notice.startsWith("Replaced"), notice);
  }

  @Test
  void moreMatchesThanTheLimitApplyAsOneReplacementAndOneUndoStep() throws Exception {
    int lines = ReplaceAllRun.SEPARATE_EDITS + 1;
    StringBuilder text = new StringBuilder("head\n");
    StringBuilder replaced = new StringBuilder("head\n");
    for (int i = 0; i < lines; i++) {
      text.append("mock line ").append(i).append('\n');
      replaced.append("x line ").append(i).append('\n');
    }
    EditorController editor = open("big.txt", text.toString());
    find.open(true);
    find.setOptions(true, false, true);
    query("mock (line)", "x $1");
    editor.selections().set(SelectionSet.caret(text.length() - 3));
    find.replaceAll();
    settle();
    assertEquals(replaced.toString(), editor.document().text().toString());
    assertEquals("Replaced " + lines + " matches", last.notice());
    assertEquals(SelectionSet.caret(replaced.length() - 3), editor.selections().selections());
    editor.undo();
    assertEquals(text.toString(), editor.document().text().toString());
  }

  @Test
  void joinedCopiesTheTextBetweenTheReplacements() {
    RopeStorage text = RopeStorage.of("a cat and a cat end");
    Replacement joined =
        ReplaceAllRun.joined(
            text,
            List.of(new Replacement(2, 3, "dog"), new Replacement(12, 3, "bird")),
            new CancellationToken());
    assertEquals(new Replacement(2, 13, "dog and a bird"), joined);
  }
}
