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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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

  /**
   * Opens a bracket pair further apart than the UI-thread scan, in a short view, with the caret
   * beside neither bracket.
   *
   * @return the closing bracket's offset
   */
  private int openFarPair() {
    open("( |" + " ".repeat(EditorController.BRACKET_SCAN + 8) + ")");
    editor.viewportChanged(0, 4);
    ui.runPending();
    return document.text().length() - 1;
  }

  /**
   * Puts the caret beside the far pair's open bracket and leaves the highlight's result queued for
   * the UI. The worker is held through the UI turn, so the result cannot land inside it.
   */
  private void searchFarBracket() throws InterruptedException {
    CountDownLatch gate = holdWorker();
    selections.set(SelectionSet.caret(0));
    ui.runPending();
    gate.countDown();
    drainWorker();
  }

  /** Puts the caret beside the far pair's open bracket and lets its highlight finish. */
  private void caretAtFarBracket() throws InterruptedException {
    searchFarBracket();
    ui.runPending();
  }

  /** Occupies the one worker until the returned latch counts down. */
  private CountDownLatch holdWorker() {
    final CountDownLatch gate = new CountDownLatch(1);
    workers.executeCpu(
        new Runnable() {
          @Override
          public void run() {
            try {
              gate.await();
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          }
        });
    return gate;
  }

  /** Waits until the one worker has run everything queued, leaving its results for the UI. */
  private void drainWorker() throws InterruptedException {
    CountDownLatch done = new CountDownLatch(1);
    workers.executeCpu(done::countDown);
    assertTrue(done.await(BOUND.toSeconds(), TimeUnit.SECONDS), "worker drained");
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

  /** Runs a line command on the marked text and returns the notices it left. */
  private List<String> noticesOf(String marked, CommandId command, String expected)
      throws InterruptedException {
    if (editor != null) {
      editor.dispose();
    }
    open(marked);
    editor.execute(command);
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return tab.shown.busy.isEmpty() || !tab.shown.busy.get(tab.shown.busy.size() - 1);
          }
        },
        "the command ended");
    assertEquals(expected, document.text().toString());
    return new ArrayList<String>(notices);
  }

  @Test
  void aLineCommandThatWouldJoinTwoBreaksNamesTheLinesItLeft() throws InterruptedException {
    String one = UiText.LINES_UNCHANGED_ONE.format(1);
    assertEquals("Left 1 line unchanged: it would merge two line breaks", one);
    assertEquals(List.of(one), noticesOf("a\rx|\n\nb", CommandId.DELETE_LINE, "a\rx\n\nb"));
    notices.clear();
    assertEquals(List.of(one), noticesOf("p\rx\n|\ny", CommandId.MOVE_LINES_UP, "p\rx\n\ny"));
    notices.clear();
    assertEquals(
        List.of(UiText.LINES_UNCHANGED.format(5)),
        noticesOf("x\ry\n\n\nz|", CommandId.SORT_LINES_ASCENDING, "x\ry\n\n\nz"));
    notices.clear();
    assertEquals(
        List.of(one), noticesOf("x\rx\n\ny\ny|", CommandId.REMOVE_DUPLICATE_LINES, "x\rx\n\ny"));
    notices.clear();
    assertEquals(
        List.of(UiText.LINES_UNCHANGED.format(2)),
        noticesOf("a\r  \nb |\r  \n", CommandId.TRIM_TRAILING_WHITESPACE, "a\r  \nb\r  \n"));
  }

  @Test
  void aLineCommandThatJoinsNothingShowsNoNotice() throws InterruptedException {
    assertEquals(List.of(), noticesOf("a\rx|\nb", CommandId.DELETE_LINE, "a\rb"));
    assertEquals(List.of(), noticesOf("a\rb\nc|", CommandId.MOVE_LINES_UP, "a\rc\nb"));
    assertEquals(List.of(), noticesOf("b\ra\nc|", CommandId.SORT_LINES_ASCENDING, "a\rb\nc"));
    assertEquals(List.of(), noticesOf("a\ra\nb|", CommandId.REMOVE_DUPLICATE_LINES, "a\rb"));
    assertEquals(List.of(), noticesOf("a \r\nb |", CommandId.TRIM_TRAILING_WHITESPACE, "a\r\nb"));
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
  void aScrollDuringASearchBeyondTheScanLeavesTheCommandRunning() throws InterruptedException {
    final int close = openFarPair();
    caretAtFarBracket();
    CountDownLatch gate = holdWorker();
    editor.execute(CommandId.GO_TO_MATCHING_BRACKET);
    editor.viewportChanged(1, 5);
    ui.runPending();
    assertEquals(List.of(true), tab.shown.busy);
    gate.countDown();
    drainWorker();
    ui.runPending();
    assertEquals(close + 1, selections.selections().primary().caret());
    assertEquals(List.of(true, false), tab.shown.busy);
  }

  @Test
  void aCaretMovedDuringASearchBeyondTheScanDropsTheCommand() throws InterruptedException {
    openFarPair();
    caretAtFarBracket();
    CountDownLatch gate = holdWorker();
    editor.execute(CommandId.GO_TO_MATCHING_BRACKET);
    selections.set(SelectionSet.caret(3));
    gate.countDown();
    drainWorker();
    ui.runPending();
    assertEquals(3, selections.selections().primary().caret());
    assertEquals(List.of(true, false), tab.shown.busy, "a dropped result clears the busy cursor");
  }

  @Test
  void aSecondBracketCommandReplacesTheRunningSearch() throws InterruptedException {
    final int close = openFarPair();
    caretAtFarBracket();
    CountDownLatch gate = holdWorker();
    editor.execute(CommandId.SELECT_TO_MATCHING_BRACKET);
    editor.execute(CommandId.GO_TO_MATCHING_BRACKET);
    gate.countDown();
    drainWorker();
    ui.runPending();
    assertTrue(selections.selections().primary().isEmpty());
    assertEquals(close + 1, selections.selections().primary().caret());
  }

  @Test
  void aBracketHighlightBeyondTheScanIsFoundOnAWorker() throws InterruptedException {
    final int close = openFarPair();
    searchFarBracket();
    assertArrayEquals(new int[0], tab.shown.brackets);
    ui.runPending();
    assertArrayEquals(new int[] {0, 1, close, close + 1}, tab.shown.brackets);
  }

  @Test
  void aBracketHighlightFromAnOlderRevisionIsDropped() throws InterruptedException {
    final int close = openFarPair();
    searchFarBracket();
    document.apply(EditBatch.replace(close + 1, 0, "y"));
    int[] untouched = {-1, -1};
    tab.shown.brackets = untouched;
    CountDownLatch gate = holdWorker();
    ui.runPending();
    assertTrue(tab.shown.brackets == untouched);
    gate.countDown();
    drainWorker();
    ui.runPending();
    assertArrayEquals(new int[] {0, 1, close, close + 1}, tab.shown.brackets);
  }

  @Test
  void aBracketHighlightForAnEarlierCaretIsDropped() throws InterruptedException {
    openFarPair();
    searchFarBracket();
    selections.set(SelectionSet.caret(3));
    int[] untouched = {-1, -1};
    tab.shown.brackets = untouched;
    ui.runPending();
    assertTrue(tab.shown.brackets == untouched);
  }

  @Test
  void anOccurrenceHighlightFromAnOlderRevisionIsDropped() throws InterruptedException {
    CountDownLatch opening = holdWorker();
    open("fo|o foo");
    opening.countDown();
    drainWorker();
    document.apply(EditBatch.replace(7, 0, " foo"));
    int[] untouched = {-1, -1};
    tab.shown.occurrences = untouched;
    CountDownLatch gate = holdWorker();
    ui.runPending();
    assertTrue(tab.shown.occurrences == untouched);
    editor.viewportChanged(0, document.text().length());
    ui.runPending();
    gate.countDown();
    drainWorker();
    ui.runPending();
    assertArrayEquals(new int[] {0, 3, 4, 7, 8, 11}, tab.shown.occurrences);
  }

  @Test
  void anEditShiftsTheHighlightsBeforeTheNextTurn() throws InterruptedException {
    open("ab|(c) ab");
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return tab.shown.occurrences.length == 4;
          }
        },
        "occurrences");
    assertArrayEquals(new int[] {2, 3, 4, 5}, tab.shown.brackets);
    document.apply(EditBatch.replace(0, 0, "x"));
    assertArrayEquals(new int[] {3, 4, 5, 6}, tab.shown.brackets);
    assertArrayEquals(new int[] {1, 3, 7, 9}, tab.shown.occurrences);
  }

  @Test
  void aHighlightEndingAfterTheEditorIsDisposedIsDropped() throws InterruptedException {
    openFarPair();
    searchFarBracket();
    int[] untouched = {-1, -1};
    tab.shown.brackets = untouched;
    editor.dispose();
    ui.runPending();
    assertTrue(tab.shown.brackets == untouched);
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
  void acceptingACompletionSkipsACaretWhosePrefixOverlapsThePrimary() throws InterruptedException {
    open("aa|a| aaxy");
    editor.execute(CommandId.COMPLETE_WORD);
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !tab.shown.completions.isEmpty();
          }
        },
        "list");
    editor.execute(CommandId.NEWLINE);
    assertEquals("aaa|a| aaxy", marked());
    assertEquals(List.of(), tab.shown.completions);
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

  /** Opens the marked text and waits for Complete Word's list of {@code size} words. */
  private void listOf(String marked, final int size) throws InterruptedException {
    open(marked);
    editor.execute(CommandId.COMPLETE_WORD);
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return tab.shown.completions.size() == size;
          }
        },
        "list");
  }

  @Test
  void theListPagesClampsAndTakesAChosenRow() throws InterruptedException {
    listOf("ab1 ab2 ab3 ab4 ab5 ab6 ab7 ab8 ab9 ab0 a|", 10);
    editor.execute(CommandId.PAGE_DOWN);
    assertEquals(8, tab.shown.completionSelected);
    editor.execute(CommandId.PAGE_DOWN);
    assertEquals(9, tab.shown.completionSelected);
    editor.execute(CommandId.CARET_UP);
    assertEquals(8, tab.shown.completionSelected);
    editor.execute(CommandId.PAGE_UP);
    assertEquals(0, tab.shown.completionSelected);
    editor.execute(CommandId.CARET_UP);
    assertEquals(0, tab.shown.completionSelected);
    String chosen = tab.shown.completions.get(2);
    editor.completionChosen(-1);
    editor.completionChosen(10);
    assertEquals(10, tab.shown.completions.size());
    editor.completionChosen(2);
    assertEquals(List.of(), tab.shown.completions);
    assertTrue(marked().endsWith(" " + chosen + "|"), marked());
    editor.completionChosen(0);
    assertTrue(marked().endsWith(" " + chosen + "|"), marked());
  }

  @Test
  void backspaceNarrowsTheListAndClosesItPastThePrefix() throws InterruptedException {
    listOf("alpha alpine al|", 2);
    editor.type("p");
    assertEquals(List.of("alpha", "alpine"), tab.shown.completions);
    editor.execute(CommandId.DELETE_BACK);
    assertEquals("alpha alpine al|", marked());
    assertEquals(List.of("alpha", "alpine"), tab.shown.completions);
    editor.execute(CommandId.DELETE_BACK);
    assertEquals(List.of(), tab.shown.completions);
  }

  @Test
  void typingAWordNoListedWordStartsWithClosesTheList() throws InterruptedException {
    listOf("alpha alpine al|", 2);
    editor.type("x");
    assertEquals(List.of(), tab.shown.completions);
    assertEquals("alpha alpine alx|", marked());
  }

  @Test
  void completeWordIgnoresADigitPrefixAndARange() throws InterruptedException {
    open("a1 1a 1|");
    editor.execute(CommandId.COMPLETE_WORD);
    drainWorker();
    ui.runPending();
    assertEquals(List.of(), tab.shown.completions);
    editor.dispose();
    open("alpha <al>");
    editor.execute(CommandId.COMPLETE_WORD);
    drainWorker();
    ui.runPending();
    assertEquals(List.of(), tab.shown.completions);
    assertEquals("alpha <al>", marked());
  }

  @Test
  void aCompletionForAnOlderRevisionShowsNoList() throws InterruptedException {
    open("alpha alpine al|");
    CountDownLatch gate = holdWorker();
    editor.execute(CommandId.COMPLETE_WORD);
    editor.type("p");
    gate.countDown();
    drainWorker();
    ui.runPending();
    assertEquals(List.of(), tab.shown.completions);
    assertEquals("alpha alpine alp|", marked());
  }

  @Test
  void escCancelsARunningLineTransform() throws InterruptedException {
    open("c\nb\na|");
    CountDownLatch gate = holdWorker();
    editor.execute(CommandId.SORT_LINES_ASCENDING);
    editor.cancel();
    gate.countDown();
    drainWorker();
    ui.runPending();
    assertEquals("c\nb\na", document.text().toString());
    assertEquals(List.of(true, false), tab.shown.busy);
    assertEquals(List.of(), notices);
  }

  @Test
  void aLineTransformAppliesAfterACaretMoveAtTheSameRevision() throws InterruptedException {
    open("c\nb\na|");
    CountDownLatch gate = holdWorker();
    editor.execute(CommandId.SORT_LINES_ASCENDING);
    selections.set(SelectionSet.caret(1));
    gate.countDown();
    drainWorker();
    ui.runPending();
    assertEquals("a\nb\nc", document.text().toString());
    assertEquals(List.of(true, false), tab.shown.busy);
  }

  @Test
  void aReadOnlyDocumentRunsNoLineTransform() {
    open("c\nb\na|");
    document.setReadOnly(true);
    editor.execute(CommandId.SORT_LINES_ASCENDING);
    ui.runPending();
    assertEquals("c\nb\na", document.text().toString());
    assertEquals(List.of(), tab.shown.busy);
  }
}
