package io.github.ajdailey.qcaret.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.editor.DisplayProjection;
import io.github.ajdailey.qcaret.app.view.ManualUiDispatcher;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.document.Document;
import io.github.ajdailey.qcaret.core.document.Selection;
import io.github.ajdailey.qcaret.core.document.SelectionModel;
import io.github.ajdailey.qcaret.core.document.SelectionSet;
import io.github.ajdailey.qcaret.core.document.UndoLimits;
import io.github.ajdailey.qcaret.core.text.LineEnding;
import io.github.ajdailey.qcaret.core.text.TextEncoding;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Editor input through the controller to the model, against a fake view. */
class EditorControllerTest {
  private static final Duration BOUND = Duration.ofSeconds(20);

  private ManualUiDispatcher ui;
  private FakeView view;
  private Workers workers;
  private final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
  private final int[] saveCancels = new int[1];
  private Document document;
  private SelectionModel selections;
  private EditorController editor;
  private FakeView.FakeTab tab;

  @BeforeEach
  void setUp() {
    ui = new ManualUiDispatcher();
    view = new FakeView(ui);
    workers =
        new Workers(
            1,
            new Thread.UncaughtExceptionHandler() {
              @Override
              public void uncaughtException(Thread thread, Throwable error) {
                failure.set(error);
              }
            });
  }

  @AfterEach
  void tearDown() {
    workers.close();
    assertNull(failure.get());
  }

  private void open(String text, LineEnding ending) {
    document =
        Document.of(
            text, TextEncoding.of(StandardCharsets.UTF_8, false), ending, UndoLimits.DEFAULTS);
    selections = document.newSelectionModel();
    DisplayProjection projection =
        new DisplayProjection(
            document, DisplayProjection.layout(document.text(), 0, new CancellationToken()));
    editor =
        new EditorController(
            projection,
            selections,
            view,
            workers,
            ui,
            new Runnable() {
              @Override
              public void run() {
                saveCancels[0]++;
              }
            });
    tab = (FakeView.FakeTab) view.addTab(projection, selections, editor, null, 0);
    editor.attach(tab);
  }

  private void carets(int... offsets) {
    Selection[] list = new Selection[offsets.length];
    for (int i = 0; i < offsets.length; i++) {
      list[i] = Selection.caret(offsets[i]);
    }
    selections.set(SelectionSet.of(List.of(list), 0));
  }

  private String text() {
    return document.text().toString();
  }

  @Test
  void typingAtEveryCaretIsOneUndoStep() {
    open("ab\ncd\n", LineEnding.LF);
    carets(1, 4);
    editor.type("x");
    editor.type("y");
    assertEquals("axyb\ncxyd\n", text());
    assertEquals(SelectionSet.of(List.of(Selection.caret(3), Selection.caret(8)), 0), sel());
    assertTrue(tab.caretShown >= 2);
    editor.undo();
    assertEquals("ab\ncd\n", text());
    assertEquals(SelectionSet.of(List.of(Selection.caret(1), Selection.caret(4)), 0), sel());
    editor.redo();
    assertEquals("axyb\ncxyd\n", text());
  }

  @Test
  void newlineInsertsTheDocumentsLineEnding() {
    open("ab\r\ncd", LineEnding.LF);
    carets(1);
    editor.newline();
    assertEquals("a\r\nb\r\ncd", text());
  }

  @Test
  void backspaceAndDeleteTakePairsWhole() {
    open("a\r\nb😀c", LineEnding.CRLF);
    carets(3);
    editor.backspace();
    assertEquals("ab😀c", text());
    carets(2);
    editor.deleteForward();
    assertEquals("abc", text());
    carets(0);
    editor.backspace();
    assertEquals("abc", text());
    editor.undo();
    assertEquals("ab😀c", text());
  }

  @Test
  void cutCopyAndPasteGoThroughTheClipboard() {
    open("one two\nthree\n", LineEnding.LF);
    selections.set(SelectionSet.of(List.of(Selection.of(0, 3), Selection.of(8, 13)), 0));
    editor.copy();
    assertEquals("one\nthree", view.clipboard);
    editor.cut();
    assertEquals(" two\n\n", text());
    view.clipboard = "1\r\n2";
    carets(0, 5);
    // As many lines as carets: one line each.
    editor.paste();
    assertEquals("1 two\n2\n", text());
    view.clipboard = "x\r\ny";
    carets(0);
    editor.paste();
    assertEquals("x\ny1 two\n2\n", text());
  }

  @Test
  void aReadOnlyDocumentIgnoresEdits() {
    open("abc", LineEnding.LF);
    document.setReadOnly(true);
    carets(1);
    editor.type("x");
    editor.backspace();
    view.clipboard = "y";
    editor.paste();
    editor.undo();
    assertEquals("abc", text());
  }

  @Test
  void viewOffsetsMapThroughVirtualBreaks() throws Exception {
    open("aaa bbb ccc\nd", LineEnding.LF);
    editor.viewColumnsChanged(4);
    editor.setWrap(true);
    assertTrue(
        ui.runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return !editor.projection().rewrapPending();
              }
            },
            BOUND));
    // Display "aaa \nbbb \nccc\nd": display 5 is model 4, the start of "bbb".
    assertEquals(1, editor.projection().lineOfRow(3));
    editor.selectFromView(5, 8);
    assertEquals(SelectionSet.of(List.of(Selection.of(4, 7)), 0), sel());
    editor.replaceFromView(10, 13, "C");
    assertEquals("aaa bbb C\nd", text());
    assertEquals(SelectionSet.caret(9), sel());
    editor.selectionsFromView(new int[] {0, 0, 5, 5}, 1);
    assertEquals(SelectionSet.of(List.of(Selection.caret(0), Selection.caret(4)), 1), sel());
    editor.cancel();
    assertEquals(SelectionSet.caret(4), sel());
    assertEquals(1, saveCancels[0]);
  }

  @Test
  void aMenuPointInASelectionKeepsThemAndOneOutsidePlacesACaret() {
    open("one two three", LineEnding.LF);
    SelectionSet two = SelectionSet.of(List.of(Selection.of(0, 3), Selection.of(4, 7)), 1);
    editor.selectionsFromView(new int[] {0, 3, 4, 7}, 1);
    assertEquals(two, sel());
    editor.menuFromView(0);
    assertEquals(two, sel());
    editor.menuFromView(7);
    assertEquals(two, sel());
    editor.menuFromView(10);
    assertEquals(SelectionSet.caret(10), sel());
    editor.menuFromView(10);
    assertEquals(SelectionSet.caret(10), sel());
  }

  @Test
  void selectAllSelectsTheWholeText() {
    open("abc\ndef", LineEnding.LF);
    editor.selectAll();
    assertEquals(SelectionSet.of(List.of(Selection.of(0, 7)), 0), sel());
  }

  private SelectionSet sel() {
    return selections.selections();
  }
}
