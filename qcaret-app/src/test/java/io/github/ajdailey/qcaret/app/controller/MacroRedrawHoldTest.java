package io.github.ajdailey.qcaret.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.macro.Macro;
import io.github.ajdailey.qcaret.app.macro.MacroStep;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A run holds its view's redraws from start to end, on a completed and on a failed run. */
class MacroRedrawHoldTest {
  private static final MacroStep.Options OFF = new MacroStep.Options(false, false, false);

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

  private static List<Boolean> held(EditorController editor) {
    return ((FakeView.FakeTab) editor.view()).shown.held;
  }

  @Test
  void aCompletedRunHoldsThenReleases() throws Exception {
    EditorController editor = t.open("abc\ndef\n");
    t.play(
        new Macro(
            null,
            null,
            List.of(new MacroStep.Typed("x"), new MacroStep.Command(CommandId.CARET_DOWN))),
        2);
    List<Boolean> held = held(editor);
    assertEquals(Boolean.TRUE, held.get(0));
    assertEquals(Boolean.FALSE, held.get(held.size() - 1));
  }

  @Test
  void aFailedRunReleases() throws Exception {
    EditorController editor = t.open("abc\n");
    t.play(
        new Macro(
            null,
            null,
            List.of(
                new MacroStep.Typed("x"),
                new MacroStep.Find("zzz", MacroStep.Direction.FORWARD, OFF))),
        1);
    List<Boolean> held = held(editor);
    assertEquals(List.of(Boolean.TRUE, Boolean.FALSE), held);
  }
}
