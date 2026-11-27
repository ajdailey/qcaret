package io.github.ajdailey.qcaret.app.macro;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** Recording: typed runs merge, incremental finds collapse, unrecorded actions show a notice. */
class MacroRecorderTest {
  private static final MacroStep.Options OFF = new MacroStep.Options(false, false, false);

  private final List<String> notices = new ArrayList<String>();
  private final MacroRecorder recorder =
      new MacroRecorder(
          new Consumer<String>() {
            @Override
            public void accept(String notice) {
              notices.add(notice);
            }
          });

  @Test
  void typedTextMergesUntilAnotherStep() {
    recorder.typed("a");
    recorder.typed("bc");
    recorder.command(CommandId.NEWLINE);
    recorder.typed("d");
    assertEquals(
        List.of(
            new MacroStep.Typed("abc"),
            new MacroStep.Command(CommandId.NEWLINE),
            new MacroStep.Typed("d")),
        recorder.steps());
  }

  @Test
  void consecutiveIncrementalFindsCollapseToTheLast() {
    recorder.found(new MacroStep.Find("a", MacroStep.Direction.INCREMENTAL, OFF));
    recorder.found(new MacroStep.Find("al", MacroStep.Direction.INCREMENTAL, OFF));
    recorder.found(new MacroStep.Find("al", MacroStep.Direction.FORWARD, OFF));
    recorder.found(new MacroStep.Find("alp", MacroStep.Direction.INCREMENTAL, OFF));
    recorder.found(new MacroStep.Find("alpha", MacroStep.Direction.INCREMENTAL, OFF));
    assertEquals(
        List.of(
            new MacroStep.Find("al", MacroStep.Direction.INCREMENTAL, OFF),
            new MacroStep.Find("al", MacroStep.Direction.FORWARD, OFF),
            new MacroStep.Find("alpha", MacroStep.Direction.INCREMENTAL, OFF)),
        recorder.steps());
  }

  @Test
  void commandsFollowTheirRecordability() {
    recorder.command(CommandId.SORT_LINES_ASCENDING);
    recorder.command(CommandId.SAVE);
    recorder.command(CommandId.ZOOM_IN);
    recorder.command(CommandId.FIND_NEXT);
    recorder.mouse();
    recorder.notRecorded(CommandId.COPY);
    assertEquals(List.of(new MacroStep.Command(CommandId.SORT_LINES_ASCENDING)), recorder.steps());
    assertEquals(
        List.of(
            UiText.MACRO_NOT_RECORDED.format(CommandId.SAVE.title()),
            UiText.MACRO_NOT_RECORDED_MOUSE.text(),
            UiText.MACRO_NOT_RECORDED.format(CommandId.COPY.title())),
        notices);
  }
}
