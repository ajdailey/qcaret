package io.github.ajdailey.qcaret.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.macro.Macro;
import io.github.ajdailey.qcaret.app.macro.MacroStep;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A step after a tab-width change in wrap mode reads the rows of the new tab width: playback waits
 * for the rewrap the change starts, so Down lands where it lands live. The run's clock stands
 * still, so both steps would run in one turn without the wait.
 */
class MacroRewrapTest {
  /** Four tabs and a word: one row of 20 columns at tab width 2, more at tab width 8. */
  private static final String START = "\t\t\t\tabcdefgh\nsecond line\nthird\n";

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

  /** Opens the text wrapped at 20 columns and tab width 2, its rows installed. */
  private EditorController openWrapped() throws Exception {
    final EditorController editor = t.open(START);
    t.app.documents().setTabWidth(2);
    editor.viewColumnsChanged(20);
    editor.setWrap(true);
    awaitRows(editor);
    assertEquals(1, editor.projection().rowOfLine(1), "one row for the first line");
    return editor;
  }

  private void awaitRows(final EditorController editor) throws InterruptedException {
    t.harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !editor.projection().rewrapPending();
          }
        },
        "rewrap installs");
    t.harness.ui.runPending();
  }

  private void downAfterTabWidth(MacroStep tabWidth) throws Exception {
    EditorController live = openWrapped();
    if (tabWidth instanceof MacroStep.TabWidth w) {
      t.app.documents().setTabWidth(w.columns());
    } else {
      t.app.execute(((MacroStep.Command) tabWidth).command());
    }
    awaitRows(live);
    assertTrue(live.projection().rowOfLine(1) > 1, "the first line wraps at tab width 8");
    live.execute(CommandId.CARET_DOWN);
    assertTrue(
        live.selections().selections().primary().caret() < START.indexOf('\n'),
        "Down stays in the first line");

    EditorController replay = openWrapped();
    MacroPlayer run =
        t.macros()
            .play(
                t.app,
                new Macro(
                    null, null, List.of(tabWidth, new MacroStep.Command(CommandId.CARET_DOWN))),
                1,
                null);
    run.setNanos(
        new LongSupplier() {
          @Override
          public long getAsLong() {
            return 0L;
          }
        });
    t.finish(run);
    MacroTesting.assertSameState(live, replay, "replay");
  }

  @Test
  void downAfterATabWidthStepReadsTheNewRows() throws Exception {
    downAfterTabWidth(new MacroStep.TabWidth(8));
  }

  @Test
  void downAfterATabWidthCommandReadsTheNewRows() throws Exception {
    downAfterTabWidth(new MacroStep.Command(CommandId.TAB_WIDTH_8));
  }
}
