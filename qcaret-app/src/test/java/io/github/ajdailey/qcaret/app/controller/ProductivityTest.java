package io.github.ajdailey.qcaret.app.controller;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.editor.Bookmarks;
import io.github.ajdailey.qcaret.app.editor.DisplayProjection;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.view.ManualUiDispatcher;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.document.BlockSelection;
import io.github.ajdailey.qcaret.core.document.Document;
import io.github.ajdailey.qcaret.core.document.EditBatch;
import io.github.ajdailey.qcaret.core.document.Selection;
import io.github.ajdailey.qcaret.core.document.SelectionModel;
import io.github.ajdailey.qcaret.core.document.SelectionSet;
import io.github.ajdailey.qcaret.core.document.UndoLimits;
import io.github.ajdailey.qcaret.core.text.LineEnding;
import io.github.ajdailey.qcaret.core.text.TextEncoding;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Auto-close, line transforms, bookmarks, bracket matching, the occurrence highlight and word
 * completion through the editor controller. Text marks a caret with {@code |} and a range with
 * {@code <} and {@code >}; the primary is the first unless {@code *} follows another.
 */
class ProductivityTest {
  private static final Duration BOUND = Duration.ofSeconds(20);

  private ManualUiDispatcher ui;
  private FakeView view;
  private Workers workers;
  private final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
  private final List<Document> open = new ArrayList<Document>();
  private final List<String> notices = new ArrayList<String>();
  private WordCompletion completion;
  private Document document;
  private SelectionModel selections;
  private EditorController editor;
  private FakeView.FakeTab tab;
  private Bookmarks bookmarks;

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
    completion =
        new WordCompletion(
            workers,
            ui,
            new Supplier<List<Document>>() {
              @Override
              public List<Document> get() {
                return open;
              }
            });
  }

  @AfterEach
  void tearDown() {
    if (editor != null) {
      editor.dispose();
    }
    workers.close();
    assertNull(failure.get());
  }

  private static Document document(String text) {
    return Document.of(
        text, TextEncoding.of(StandardCharsets.UTF_8, false), LineEnding.LF, UndoLimits.DEFAULTS);
  }

  private void open(String marked) {
    StringBuilder text = new StringBuilder();
    List<Selection> list = new ArrayList<Selection>();
    int primary = 0;
    int anchor = -1;
    for (char c : marked.toCharArray()) {
      switch (c) {
        case '|' -> list.add(Selection.caret(text.length()));
        case '<' -> anchor = text.length();
        case '>' -> list.add(Selection.of(anchor, text.length()));
        case '*' -> primary = list.size() - 1;
        default -> text.append(c);
      }
    }
    document = document(text.toString());
    open.add(document);
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
                // No save runs.
              }
            });
    bookmarks = new Bookmarks(document);
    editor.setBookmarks(bookmarks);
    editor.setCompletion(completion);
    editor.setNoticeListener(
        new Consumer<String>() {
          @Override
          public void accept(String message) {
            notices.add(message);
          }
        });
    tab = (FakeView.FakeTab) view.addTab(projection, selections, editor, null, 0);
    editor.attach(tab);
    selections.set(SelectionSet.of(list, primary));
    editor.viewportChanged(0, document.text().length());
    ui.runPending();
  }

  private String marked() {
    String text = document.text().toString();
    SelectionSet set = selections.selections();
    StringBuilder out = new StringBuilder();
    for (int i = 0; i <= text.length(); i++) {
      for (int k = 0; k < set.size(); k++) {
        Selection s = set.selections().get(k);
        String star = k == set.primaryIndex() && k > 0 ? "*" : "";
        if (s.isEmpty() && s.caret() == i) {
          out.append('|').append(star);
        } else if (!s.isEmpty() && s.start() == i) {
          out.append('<');
        } else if (!s.isEmpty() && s.end() == i) {
          out.append('>').append(star);
        }
      }
      if (i < text.length()) {
        out.append(text.charAt(i));
      }
    }
    return out.toString();
  }

  private void await(BooleanSupplier condition, String what) throws InterruptedException {
    assertTrue(ui.runUntil(condition, BOUND), what);
    ui.runPending();
  }

  private void typeEach(String chars) {
    for (char c : chars.toCharArray()) {
      editor.type(String.valueOf(c));
    }
  }

  @Test
  void typedPairsCloseAndTypeOverAndBackspaceDeletesThePair() {
    open("f|\ng|");
    typeEach("(x");
    assertEquals("f(x|)\ng(x|)", marked());
    typeEach(")(");
    assertEquals("f(x)(|)\ng(x)(|)", marked());
    editor.backspace();
    assertEquals("f(x)|\ng(x)|", marked());
    editor.undo();
    assertEquals("f(x)(|)\ng(x)(|)", marked());
  }

  @Test
  void aBlockTypesWithoutPairs() {
    open("|ab\ncd");
    selections.setBlock(new BlockSelection(0, 1, 1, 1), 4);
    editor.type("(");
    assertEquals("a(b\nc(d", document.text().toString());
  }

  @Test
  void sortRunsOnAWorkerAsOneStep() throws InterruptedException {
    open("c\nb\na|");
    editor.execute(CommandId.SORT_LINES_ASCENDING);
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return document.text().toString().equals("a\nb\nc");
          }
        },
        "sorted");
    // Busy from the command to the apply, which ran in the same UI task as the last call.
    assertEquals(List.of(true, false), tab.shown.busy);
    editor.execute(CommandId.TRIM_TRAILING_WHITESPACE);
    editor.undo();
    assertEquals("c\nb\na", document.text().toString());
  }

  @Test
  void aLineTransformForAnOlderRevisionIsDroppedWithANotice() throws InterruptedException {
    open("b \na|");
    editor.execute(CommandId.REMOVE_DUPLICATE_LINES);
    editor.execute(CommandId.TRIM_TRAILING_WHITESPACE);
    document.apply(EditBatch.replace(0, 0, "x"));
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !notices.isEmpty();
          }
        },
        "notice");
    assertEquals(List.of(UiText.LINES_STALE.text()), notices);
    assertEquals("xb \na", document.text().toString());
    assertEquals(List.of(true, true, false), tab.shown.busy);
  }

  @Test
  void bookmarksToggleAtEachCaretAndFollowEdits() {
    open("a|\nb\nc|\nd");
    editor.execute(CommandId.TOGGLE_BOOKMARK);
    assertArrayEquals(new int[] {0, 2}, bookmarks.lines());
    selections.set(SelectionSet.caret(0));
    editor.newline();
    assertArrayEquals(new int[] {1, 3}, bookmarks.lines());
    editor.execute(CommandId.NEXT_BOOKMARK);
    assertEquals(document.text().offsetOfLine(3), selections.selections().primary().caret());
    editor.execute(CommandId.NEXT_BOOKMARK);
    assertEquals(document.text().offsetOfLine(1), selections.selections().primary().caret());
    editor.execute(CommandId.PREVIOUS_BOOKMARK);
    assertEquals(document.text().offsetOfLine(3), selections.selections().primary().caret());
    editor.execute(CommandId.TOGGLE_BOOKMARK);
    assertArrayEquals(new int[] {1}, bookmarks.lines());
    editor.execute(CommandId.CLEAR_BOOKMARKS);
    assertArrayEquals(new int[0], bookmarks.lines());
    assertTrue(tab.shown.bookmarks == bookmarks);
  }

  @Test
  void goToAndSelectToMatchingBracketAtEveryCaret() {
    open("|{ a } x (b|)");
    editor.execute(CommandId.GO_TO_MATCHING_BRACKET);
    assertEquals("{ a }| x |(b)", marked());
    editor.execute(CommandId.SELECT_TO_MATCHING_BRACKET);
    assertEquals("<{ a }> x <(b)>", marked());
  }

  @Test
  void aPartnerBeyondTheScanIsFoundOnAWorker() throws InterruptedException {
    open("|(" + "x".repeat(EditorController.BRACKET_SCAN + 10) + ")");
    editor.execute(CommandId.GO_TO_MATCHING_BRACKET);
    final int end = document.text().length();
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return selections.selections().primary().caret() == end;
          }
        },
        "moved past the partner");
  }

  @Test
  void theBracketBesideThePrimaryCaretAndItsPartnerAreHighlighted() throws InterruptedException {
    open("a(b|)c");
    assertArrayEquals(new int[] {1, 2, 3, 4}, tab.shown.brackets);
    selections.set(SelectionSet.caret(0));
    ui.runPending();
    assertArrayEquals(new int[0], tab.shown.brackets);
  }

  @Test
  void theWordAtTheCaretIsHighlightedWhereItOccursTwice() throws InterruptedException {
    open("foo bar fo|o foo_");
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return tab.shown.occurrences.length == 4;
          }
        },
        "occurrences");
    assertArrayEquals(new int[] {0, 3, 8, 11}, tab.shown.occurrences);
    selections.set(SelectionSet.caret(5));
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return tab.shown.occurrences.length == 0;
          }
        },
        "bar occurs once");
  }

  @Test
  void completionListsWordsOfEveryOpenDocumentAndInsertsTheChoice() throws InterruptedException {
    open.add(document("alphabet alpine"));
    open("alpha al|");
    editor.execute(CommandId.COMPLETE_WORD);
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !tab.shown.completions.isEmpty();
          }
        },
        "list");
    assertEquals(List.of("alpha", "alphabet", "alpine"), tab.shown.completions);
    typeEach("pi");
    assertEquals(List.of("alpine"), tab.shown.completions);
    editor.execute(CommandId.NEWLINE);
    assertEquals("alpha alpine|", marked());
    assertEquals(List.of(), tab.shown.completions);
    editor.undo();
    assertEquals("alpha alpi|", marked());
  }

  @Test
  void theListMovesWithArrowsAndClosesOnEsc() throws InterruptedException {
    open("alpha alpine al|");
    editor.execute(CommandId.COMPLETE_WORD);
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return tab.shown.completions.size() == 2;
          }
        },
        "list");
    editor.execute(CommandId.CARET_DOWN);
    assertEquals(1, tab.shown.completionSelected);
    editor.execute(CommandId.CARET_DOWN);
    assertEquals(1, tab.shown.completionSelected);
    editor.execute(CommandId.CANCEL);
    assertEquals(List.of(), tab.shown.completions);
    assertEquals("alpha alpine al|", marked());
  }

  @Test
  void anExplicitRequestWithOneWordInsertsIt() throws InterruptedException {
    open("alpha a|");
    editor.execute(CommandId.COMPLETE_WORD);
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return document.text().toString().equals("alpha alpha");
          }
        },
        "inserted");
    assertEquals(List.of(), tab.shown.completions);
  }

  @Test
  void theListOpensOnItsOwnAfterTheConfiguredChars() throws InterruptedException {
    open("alpha alpine |");
    editor.setCompletionChars(2);
    editor.type("a");
    ui.runPending();
    assertEquals(List.of(), tab.shown.completions);
    editor.type("l");
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return tab.shown.completions.size() == 2;
          }
        },
        "list");
  }
}
