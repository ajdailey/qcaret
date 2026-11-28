package io.github.ajdailey.qcaret.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.macro.Macro;
import io.github.ajdailey.qcaret.app.macro.MacroFormat;
import io.github.ajdailey.qcaret.app.macro.MacroStep;
import io.github.ajdailey.qcaret.app.view.SequenceForm;
import io.github.ajdailey.qcaret.app.view.UnsavedChoice;
import io.github.ajdailey.qcaret.core.document.DocumentChange;
import io.github.ajdailey.qcaret.core.document.DocumentListener;
import io.github.ajdailey.qcaret.core.document.SelectionSet;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Macro recording and playback through the window's controllers: every step kind recorded live
 * replays to the same text and selections on a copy, also after a save and load of the text format;
 * one Undo reverts a completed, failed or canceled run; repeats, failures, the input hold and the
 * run's end when its editor closes.
 */
class MacroPlaybackTest {
  private static final MacroStep.Options OFF = new MacroStep.Options(false, false, false);
  private static final String START = "beta alpha\ngamma alpha one\ndelta\nalpha end\n";

  @TempDir java.nio.file.Path temp;

  private MacroTesting t;

  @BeforeEach
  void create() {
    t = new MacroTesting(temp);
  }

  @AfterEach
  void close() throws InterruptedException {
    t.close();
  }

  private static Macro macro(MacroStep... steps) {
    return new Macro(null, null, List.of(steps));
  }

  /** Makes each UI turn run one step, so a test can act between steps. */
  private static void oneStepPerTurn(MacroPlayer run) {
    run.setNanos(
        new LongSupplier() {
          private long now;

          @Override
          public long getAsLong() {
            now += MacroPlayer.TURN_NANOS;
            return now;
          }
        });
  }

  @Test
  void everyStepKindRecordedLiveReplaysTheSame() throws Exception {
    final EditorController a = t.open(START);
    t.app.execute(CommandId.RECORD_MACRO);
    assertTrue(t.macros().recording(t.app));
    a.execute(CommandId.LINE_END);
    a.type("(");
    a.type("x");
    a.type(")");
    t.app.execute(CommandId.NEWLINE);
    t.app.execute(CommandId.DOCUMENT_START);
    t.app.execute(CommandId.FIND);
    t.find.setText("alpha");
    t.settle(a);
    t.find.findNext();
    t.settle(a);
    t.find.findPrevious();
    t.settle(a);
    t.find.setReplacement("ALPHA");
    t.find.replace();
    t.settle(a);
    t.app.execute(CommandId.GO_TO_LINE);
    t.harness.view.goTo.accept("3:2");
    t.app.execute(CommandId.TAB_WIDTH_OTHER);
    t.harness.view.tabWidthAnswer.accept(3);
    a.execute(CommandId.INSERT_TAB);
    t.app.dateTime().insert("'stamp'");
    t.app.execute(CommandId.ADD_CARET_BELOW);
    t.app.execute(CommandId.INSERT_NUMBER_SEQUENCE);
    t.harness.view.sequenceAnswer.accept(new SequenceForm("7", "2", 3, 0));
    t.app.execute(CommandId.CANCEL);
    a.type(" be");
    final long typed = a.document().revision();
    final FakeView.FakeTab tab = t.harness.view.selected;
    a.execute(CommandId.COMPLETE_WORD);
    t.harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !tab.shown.completions.isEmpty() || a.document().revision() != typed;
          }
        },
        "completion");
    if (!tab.shown.completions.isEmpty()) {
      a.completionChosen(tab.shown.completions.indexOf("beta"));
    }
    a.preeditFromView("ime");
    a.commitComposition();
    a.execute(CommandId.BLOCK_DOWN);
    a.execute(CommandId.BLOCK_RIGHT);
    a.type("z");
    a.execute(CommandId.CANCEL);
    t.app.execute(CommandId.SELECT_LINE);
    t.settle(a);
    t.app.execute(CommandId.BASE64_ENCODE);
    t.settle(a);
    t.app.execute(CommandId.SORT_LINES_DESCENDING);
    t.settle(a);
    t.find.replaceAll();
    t.settle(a);
    t.app.execute(CommandId.RECORD_MACRO);
    assertFalse(t.macros().recording(t.app));

    Macro recorded = t.macros().recorded();
    assertNotNull(recorded);
    Set<Class<?>> kinds = new HashSet<Class<?>>();
    for (MacroStep step : recorded.steps()) {
      kinds.add(step.getClass());
    }
    assertEquals(MacroStep.class.getPermittedSubclasses().length, kinds.size(), kinds.toString());
    assertTrue(
        recorded
            .steps()
            .contains(new MacroStep.Find("alpha", MacroStep.Direction.INCREMENTAL, OFF)));
    assertEquals(
        UiText.MACRO_RECORDED.format(
            UiText.count(recorded.steps().size(), UiText.COUNT_STEP, UiText.COUNT_STEPS)),
        t.lastNotice());

    EditorController b = t.open(START);
    MacroPlayer run = t.play(recorded, 1);
    assertEquals(
        UiText.MACRO_RAN.format(UiText.count(1, UiText.COUNT_TIME, UiText.COUNT_TIMES)),
        run.notice());
    MacroTesting.assertSameState(a, b, "replay");
    assertEquals(3, b.document().tabWidth());

    Macro loaded = MacroFormat.parse(MacroFormat.write(recorded.named("all")), "all");
    assertEquals(recorded.steps(), loaded.steps());
    EditorController c = t.open(START);
    t.play(loaded, 1);
    MacroTesting.assertSameState(a, c, "replay after save and load");

    b.execute(CommandId.UNDO);
    assertEquals(START, MacroTesting.text(b));
    assertEquals(SelectionSet.caret(0), b.selections().selections());
  }

  @Test
  void workerStepsApplyBeforeTheNextStep() throws Exception {
    String start = "foo bar foo\nbaz foo\nqux\n";
    EditorController a = t.open(start);
    t.app.execute(CommandId.RECORD_MACRO);
    a.execute(CommandId.SELECT_ALL_OCCURRENCES);
    t.settle(a);
    a.type("Q");
    a.execute(CommandId.ADD_NEXT_OCCURRENCE);
    t.settle(a);
    t.app.execute(CommandId.SELECT_ALL);
    t.app.execute(CommandId.URL_ENCODE);
    t.settle(a);
    t.app.execute(CommandId.SORT_LINES_DESCENDING);
    t.settle(a);
    a.type("!");
    t.app.execute(CommandId.RECORD_MACRO);
    EditorController b = t.open(start);
    t.play(t.macros().recorded(), 1);
    MacroTesting.assertSameState(a, b, "worker steps");
  }

  @Test
  void aFailedStepStopsTheRunAndOneUndoRevertsIt() throws Exception {
    EditorController editor = t.open("abc\n");
    MacroPlayer run =
        t.play(
            macro(
                new MacroStep.Typed("x"),
                new MacroStep.Find("zzz", MacroStep.Direction.FORWARD, OFF),
                new MacroStep.Typed("y")),
            1);
    assertEquals(UiText.MACRO_STOPPED.format(1, 1, 2, UiText.MACRO_NO_MATCH.text()), run.notice());
    assertEquals("xabc\n", MacroTesting.text(editor));
    editor.execute(CommandId.UNDO);
    assertEquals("abc\n", MacroTesting.text(editor));
    assertEquals(SelectionSet.caret(0), editor.selections().selections());

    editor = t.open("a!c\n");
    run =
        t.play(
            macro(
                new MacroStep.Command(CommandId.SELECT_ALL),
                new MacroStep.Command(CommandId.BASE64_DECODE)),
            2);
    assertTrue(run.notice().startsWith(UiText.MACRO_STOPPED.format(1, 2, 2, "")), run.notice());
    assertEquals("a!c\n", MacroTesting.text(editor));
  }

  @Test
  void aDisabledCommandFailsItsStep() throws Exception {
    EditorController editor = t.open("abc\n");
    MacroPlayer run = t.play(macro(new MacroStep.Command(CommandId.UNDO)), 1);
    assertEquals(
        UiText.MACRO_STOPPED.format(1, 1, 1, UiText.MACRO_DISABLED.format(CommandId.UNDO.title())),
        run.notice());
    assertEquals("abc\n", MacroTesting.text(editor));
  }

  @Test
  void escStopsTheRunAndOneUndoRevertsIt() throws Exception {
    EditorController editor = t.open("abc\n");
    MacroPlayer run =
        t.macros()
            .play(
                t.app,
                macro(new MacroStep.Typed("x"), new MacroStep.Typed("y"), new MacroStep.Typed("z")),
                1,
                null);
    oneStepPerTurn(run);
    t.harness.ui.runQueued();
    assertEquals("xabc\n", MacroTesting.text(editor));
    editor.execute(CommandId.CANCEL);
    assertTrue(run.ended());
    assertEquals(UiText.MACRO_STOPPED.format(1, 1, 2, UiText.MACRO_CANCELED.text()), run.notice());
    t.harness.ui.runPending();
    assertEquals("xabc\n", MacroTesting.text(editor));
    editor.execute(CommandId.UNDO);
    assertEquals("abc\n", MacroTesting.text(editor));
  }

  /** macOS gate MC-5: Esc after a 2-step iteration named step 3. */
  @Test
  void escAfterAnIterationsLastStepNamesTheNextIterationsFirstStep() throws Exception {
    EditorController editor = t.open("abc\n");
    MacroPlayer run =
        t.macros().play(t.app, macro(new MacroStep.Typed("x"), new MacroStep.Typed("y")), 3, null);
    oneStepPerTurn(run);
    t.harness.ui.runQueued();
    t.harness.ui.runQueued();
    assertEquals("xyabc\n", MacroTesting.text(editor));
    editor.execute(CommandId.CANCEL);
    assertEquals(UiText.MACRO_STOPPED.format(2, 3, 1, UiText.MACRO_CANCELED.text()), run.notice());
  }

  @Test
  void escAfterAnIterationsLastStepOfTheCountEndsTheRunNormally() throws Exception {
    EditorController editor = t.open("abc\n");
    MacroPlayer run =
        t.macros().play(t.app, macro(new MacroStep.Typed("x"), new MacroStep.Typed("y")), 1, null);
    oneStepPerTurn(run);
    t.harness.ui.runQueued();
    t.harness.ui.runQueued();
    editor.execute(CommandId.CANCEL);
    assertEquals(
        UiText.MACRO_RAN.format(UiText.count(1, UiText.COUNT_TIME, UiText.COUNT_TIMES)),
        run.notice());
  }

  @Test
  void escWhileACommandStepIsAwaitedNamesThatStep() throws Exception {
    EditorController editor = t.open("abc\n");
    MacroPlayer run =
        t.macros()
            .play(
                t.app,
                macro(new MacroStep.Typed("x"), new MacroStep.Command(CommandId.SELECT_LINE)),
                1,
                null);
    oneStepPerTurn(run);
    t.harness.ui.runQueued();
    t.harness.ui.runQueued();
    editor.execute(CommandId.CANCEL);
    assertEquals(UiText.MACRO_STOPPED.format(1, 1, 2, UiText.MACRO_CANCELED.text()), run.notice());
  }

  @Test
  void inputToTheHeldDocumentIsDroppedInEveryView() throws Exception {
    EditorController editor = t.open("abc\n");
    t.app.execute(CommandId.SPLIT_VERTICAL);
    EditorController other = t.app.documents().activeEditor();
    t.app.execute(CommandId.FOCUS_OTHER_VIEW);
    assertTrue(t.app.documents().activeEditor() == editor);
    MacroPlayer run =
        t.macros().play(t.app, macro(new MacroStep.Typed("x"), new MacroStep.Typed("y")), 1, null);
    oneStepPerTurn(run);
    t.harness.ui.runQueued();
    editor.type("Q");
    other.type("Q");
    other.execute(CommandId.DELETE_LINE);
    other.replaceFromView(0, 0, "Q");
    t.app.execute(CommandId.DUPLICATE);
    t.find.setText("abc");
    t.find.replaceAll();
    t.app.execute(CommandId.ZOOM_IN);
    assertEquals("xabc\n", MacroTesting.text(editor));
    assertFalse(run.ended());
    t.finish(run);
    assertEquals("xyabc\n", MacroTesting.text(editor));
    editor.type("!");
    assertEquals("xy!abc\n", MacroTesting.text(editor));
  }

  @Test
  void closingTheRunsTabEndsTheRunAndItsGroup() throws Exception {
    t.open("other\n");
    EditorController editor = t.open("abc\n");
    MacroPlayer run =
        t.macros().play(t.app, macro(new MacroStep.Typed("x"), new MacroStep.Typed("y")), 1, null);
    oneStepPerTurn(run);
    t.harness.ui.runQueued();
    t.app.documents().closeActive();
    assertNotNull(t.harness.view.unsaved);
    t.harness.view.unsaved.accept(UnsavedChoice.DISCARD);
    t.harness.ui.runPending();
    assertTrue(run.ended());
    assertEquals(
        UiText.MACRO_STOPPED.format(1, 1, 2, UiText.MACRO_TAB_CHANGED.text()), run.notice());
    assertNull(t.macros().run(t.app));
  }

  @Test
  void selectingAnotherTabStopsTheRun() throws Exception {
    t.open("other\n");
    EditorController editor = t.open("abc\n");
    MacroPlayer run =
        t.macros().play(t.app, macro(new MacroStep.Typed("x"), new MacroStep.Typed("y")), 1, null);
    oneStepPerTurn(run);
    t.harness.ui.runQueued();
    t.app.documents().selectNext(true);
    assertTrue(run.ended());
    assertEquals("xabc\n", MacroTesting.text(editor));
    assertTrue(editor.document().canUndo());
  }

  @Test
  void quittingDuringARunEndsItWithoutANotice() throws Exception {
    EditorController editor = t.open("abc\n");
    MacroPlayer run =
        t.macros().play(t.app, macro(new MacroStep.Typed("x"), new MacroStep.Typed("y")), 1, null);
    oneStepPerTurn(run);
    t.harness.ui.runQueued();
    t.macros().windowStopped(t.app);
    assertTrue(run.ended());
    assertNull(run.notice());
    assertTrue(editor.document().canUndo());
  }

  @Test
  void aCountedRunRepeats() throws Exception {
    EditorController editor = t.open("a\nb\nc\nd\n");
    MacroPlayer run =
        t.play(
            macro(
                new MacroStep.Typed("-"),
                new MacroStep.Command(CommandId.CARET_DOWN),
                new MacroStep.Command(CommandId.LINE_START)),
            3);
    assertEquals("-a\n-b\n-c\nd\n", MacroTesting.text(editor));
    assertEquals(3, run.iterations());
    assertEquals(
        UiText.MACRO_RAN.format(UiText.count(3, UiText.COUNT_TIME, UiText.COUNT_TIMES)),
        run.notice());
    editor.execute(CommandId.UNDO);
    assertEquals("a\nb\nc\nd\n", MacroTesting.text(editor));
  }

  /**
   * A run of many iterations undoes and redoes as one document change, so the views update once:
   * undoing a 15,471-iteration run edit by edit held the UI thread for 20 s on Windows.
   */
  @Test
  void aLongRunUndoesAndRedoesAsOneChange() throws Exception {
    StringBuilder text = new StringBuilder();
    for (int i = 0; i < 400; i++) {
      text.append("line ").append(i).append('\n');
    }
    EditorController editor = t.open(text.toString());
    MacroPlayer run =
        t.play(
            macro(
                new MacroStep.Command(CommandId.LINE_END),
                new MacroStep.Typed(" // ok"),
                new MacroStep.Command(CommandId.CARET_DOWN)),
            400);
    assertEquals(400, run.iterations());
    String ran = MacroTesting.text(editor);
    final List<DocumentChange> changes = new ArrayList<DocumentChange>();
    editor
        .document()
        .addListener(
            new DocumentListener() {
              @Override
              public void textChanged(DocumentChange change) {
                changes.add(change);
              }

              @Override
              public void dirtyChanged(boolean dirty) {}
            });
    editor.execute(CommandId.UNDO);
    assertEquals(text.toString(), MacroTesting.text(editor));
    assertEquals(1, changes.size());
    assertEquals(400, changes.get(0).edits().size());
    editor.execute(CommandId.REDO);
    assertEquals(ran, MacroTesting.text(editor));
    assertEquals(2, changes.size());
  }

  @Test
  void aRunToTheEndStopsAtAFindWithNoMatch() throws Exception {
    EditorController editor = t.open("x1 x2 x3 y\n");
    MacroPlayer run =
        t.play(
            macro(
                new MacroStep.Find("x", MacroStep.Direction.FORWARD, OFF),
                new MacroStep.Typed("!")),
            0);
    assertEquals("!1 !2 !3 y\n", MacroTesting.text(editor));
    assertEquals(3, run.iterations());
    assertEquals(
        UiText.MACRO_RAN.format(UiText.count(3, UiText.COUNT_TIME, UiText.COUNT_TIMES)),
        run.notice());
  }

  @Test
  void aRunToTheEndWithoutProgressStopsAfterOneIteration() throws Exception {
    t.open("abc\n");
    MacroPlayer run = t.play(macro(new MacroStep.Command(CommandId.CARET_LEFT)), 0);
    assertEquals(1, run.iterations());
  }

  @Test
  void aRunToTheEndCrossesADocumentLargerThanTheBlocks() throws Exception {
    int lines = 1200;
    String line = "x".repeat(999) + "\n";
    EditorController editor = t.open(line.repeat(lines));
    MacroPlayer run = t.play(macro(new MacroStep.Command(CommandId.CARET_DOWN)), 0);
    assertEquals(lines, run.iterations());
    assertEquals(lines * line.length(), editor.selections().selections().primary().caret());
  }

  @Test
  void aMacroWithUndoPlaysWithoutTheSingleGroup() throws Exception {
    EditorController a = t.open("abc\n");
    t.app.execute(CommandId.RECORD_MACRO);
    a.type("1");
    a.type("2");
    t.app.execute(CommandId.UNDO);
    a.type("3");
    t.app.execute(CommandId.REDO);
    t.app.execute(CommandId.RECORD_MACRO);
    Macro recorded = t.macros().recorded();
    assertEquals(
        List.of(
            new MacroStep.Typed("12"),
            new MacroStep.Command(CommandId.UNDO),
            new MacroStep.Typed("3")),
        recorded.steps());
    assertTrue(recorded.undoes());
    EditorController b = t.open("abc\n");
    t.play(recorded, 1);
    MacroTesting.assertSameState(a, b, "undo replay");
    b.execute(CommandId.UNDO);
    assertEquals("abc\n", MacroTesting.text(b));
    b.execute(CommandId.REDO);
    assertEquals("3abc\n", MacroTesting.text(b));
  }

  @Test
  void recordingLeavesOutUnrecordedAndDisabledCommandsWithNotices() throws Exception {
    EditorController editor = t.open("abc\n");
    t.app.execute(CommandId.RECORD_MACRO);
    editor.execute(CommandId.UNDO);
    t.app.execute(CommandId.BOOKMARKS_LIST);
    assertEquals(
        UiText.MACRO_NOT_RECORDED.format(CommandId.BOOKMARKS_LIST.title()), t.lastNotice());
    editor.selectFromView(1, 2);
    assertEquals(UiText.MACRO_NOT_RECORDED_MOUSE.text(), t.lastNotice());
    t.app.execute(CommandId.ZOOM_IN);
    editor.execute(CommandId.CARET_RIGHT);
    t.app.execute(CommandId.CARET_RIGHT);
    assertNull(t.macros().play(t.app, macro(new MacroStep.Typed("x")), 1, null));
    assertEquals(UiText.MACRO_BUSY.text(), t.lastNotice());
    t.app.execute(CommandId.RECORD_MACRO);
    assertEquals(
        List.of(
            new MacroStep.Command(CommandId.CARET_RIGHT),
            new MacroStep.Command(CommandId.CARET_RIGHT)),
        t.macros().recorded().steps());
  }

  @Test
  void aRunIsRefusedOnAReadOnlyDocumentAndWithoutARecording() throws Exception {
    t.open("abc\n");
    t.app.execute(CommandId.PLAY_RECORDED_MACRO);
    assertEquals(UiText.MACRO_NONE.text(), t.lastNotice());
    CommandStates states = new CommandStates(t.app);
    assertFalse(states.enabled(CommandId.PLAY_RECORDED_MACRO));
    assertTrue(states.enabled(CommandId.RECORD_MACRO));
    t.app.execute(CommandId.READ_ONLY);
    assertNull(t.macros().play(t.app, macro(new MacroStep.Typed("x")), 1, null));
    assertEquals(UiText.MACRO_READ_ONLY.text(), t.lastNotice());
    t.app.execute(CommandId.RECORD_MACRO);
    assertTrue(states.checked(CommandId.RECORD_MACRO));
    t.app.execute(CommandId.RECORD_MACRO);
    assertFalse(states.checked(CommandId.RECORD_MACRO));
    assertNull(t.macros().recorded());
  }
}
