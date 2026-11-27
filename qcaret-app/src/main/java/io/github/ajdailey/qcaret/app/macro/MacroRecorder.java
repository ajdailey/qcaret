package io.github.ajdailey.qcaret.app.macro;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.core.edit.NumberSequence;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Collects one recording's steps. Consecutive typed text merges into one {@link MacroStep.Typed}
 * step, and consecutive {@link MacroStep.Direction#INCREMENTAL} finds collapse to the last. A
 * {@link Recordability#NOTICE} command and a mouse action are left out with a notice naming them.
 * Confined to the UI thread.
 */
public final class MacroRecorder {
  private final List<MacroStep> steps = new ArrayList<MacroStep>();
  private final Consumer<String> notices;

  /**
   * Creates an empty recording.
   *
   * @param notices shows a notice in the status bar
   */
  public MacroRecorder(Consumer<String> notices) {
    this.notices = notices;
  }

  /**
   * Records a command that ran, by its {@link Recordability}.
   *
   * @param command the command
   */
  public void command(CommandId command) {
    switch (Recordability.of(command)) {
      case COMMAND -> steps.add(new MacroStep.Command(command));
      case NOTICE -> notices.accept(UiText.MACRO_NOT_RECORDED.format(command.title()));
      case OWN_STEP, SILENT -> {
        // An own step records its result; a silent command records nothing.
      }
    }
  }

  /**
   * Records typed text, merged into a typed step just before.
   *
   * @param text the text
   */
  public void typed(String text) {
    if (text.isEmpty()) {
      return;
    }
    if (last() instanceof MacroStep.Typed before) {
      steps.set(steps.size() - 1, new MacroStep.Typed(before.text() + text));
    } else {
      steps.add(new MacroStep.Typed(text));
    }
  }

  /**
   * Records an input method's committed text.
   *
   * @param text the text
   */
  public void inserted(String text) {
    if (!text.isEmpty()) {
      steps.add(new MacroStep.Insert(text));
    }
  }

  /**
   * Records a word chosen from the completion list.
   *
   * @param word the word
   */
  public void completed(String word) {
    steps.add(new MacroStep.Complete(word));
  }

  /**
   * Records a find; an incremental find replaces an incremental find just before.
   *
   * @param step the find
   */
  public void found(MacroStep.Find step) {
    if (step.direction() == MacroStep.Direction.INCREMENTAL
        && last() instanceof MacroStep.Find before
        && before.direction() == MacroStep.Direction.INCREMENTAL) {
      steps.set(steps.size() - 1, step);
    } else {
      steps.add(step);
    }
  }

  /**
   * Records a Replace or Replace All.
   *
   * @param step the replace
   */
  public void replaced(MacroStep.Replace step) {
    steps.add(step);
  }

  /**
   * Records Go to Line's answer.
   *
   * @param line from 1
   * @param column from 1
   */
  public void goToLine(int line, int column) {
    steps.add(new MacroStep.GoToLine(line, column));
  }

  /**
   * Records Tab Width > Other's answer.
   *
   * @param columns the width
   */
  public void tabWidth(int columns) {
    steps.add(new MacroStep.TabWidth(columns));
  }

  /**
   * Records Insert Date and Time's format.
   *
   * @param pattern the format
   */
  public void dateTime(String pattern) {
    steps.add(new MacroStep.DateTime(pattern));
  }

  /**
   * Records Insert Number Sequence's values.
   *
   * @param sequence the values
   */
  public void sequence(NumberSequence sequence) {
    steps.add(new MacroStep.Sequence(sequence));
  }

  /**
   * Shows that a command is not recorded, whatever its {@link Recordability}: another pane than the
   * editor took it.
   *
   * @param command the command
   */
  public void notRecorded(CommandId command) {
    notices.accept(UiText.MACRO_NOT_RECORDED.format(command.title()));
  }

  /** Shows that a mouse action is not recorded. */
  public void mouse() {
    notices.accept(UiText.MACRO_NOT_RECORDED_MOUSE.text());
  }

  /**
   * Returns the steps so far.
   *
   * @return a copy
   */
  public List<MacroStep> steps() {
    return List.copyOf(steps);
  }

  private MacroStep last() {
    return steps.isEmpty() ? null : steps.get(steps.size() - 1);
  }
}
