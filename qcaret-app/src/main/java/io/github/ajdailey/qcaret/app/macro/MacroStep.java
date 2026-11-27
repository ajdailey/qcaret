package io.github.ajdailey.qcaret.app.macro;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.core.edit.NumberSequence;
import java.util.Objects;

/** One recorded operation of a macro; {@link MacroFormat} gives each kind's line. */
public sealed interface MacroStep {
  /**
   * A command run as the menu, a key or the palette runs it.
   *
   * @param command the command
   */
  record Command(CommandId command) implements MacroStep {
    /** Checks the command. */
    public Command {
      Objects.requireNonNull(command);
    }
  }

  /**
   * Typed text, replayed one code point per call so auto-close and smart indent act as when keyed.
   *
   * @param text one or more typed chars
   */
  record Typed(String text) implements MacroStep {
    /** Checks the text. */
    public Typed {
      if (text.isEmpty()) {
        throw new IllegalArgumentException("empty typed text");
      }
    }
  }

  /**
   * An input method's committed text, replayed as one typed call.
   *
   * @param text the text
   */
  record Insert(String text) implements MacroStep {
    /** Checks the text. */
    public Insert {
      if (text.isEmpty()) {
        throw new IllegalArgumentException("empty inserted text");
      }
    }
  }

  /**
   * A word chosen from the completion list; replaces the identifier prefix before each caret.
   *
   * @param word the word
   */
  record Complete(String word) implements MacroStep {
    /** Checks the word. */
    public Complete {
      if (word.isEmpty()) {
        throw new IllegalArgumentException("empty word");
      }
    }
  }

  /** Where a find searches from. */
  enum Direction {
    /** Forward from the primary selection's end: Find Next. */
    FORWARD("forward"),
    /** Backward from the primary selection's start: Find Previous. */
    BACKWARD("backward"),
    /** Forward from the primary selection's start, so the selection itself matches: typing. */
    INCREMENTAL("incremental");

    private final String word;

    Direction(String word) {
      this.word = word;
    }

    /**
     * Returns the word the format writes.
     *
     * @return for example {@code forward}
     */
    public String word() {
      return word;
    }
  }

  /**
   * The find bar's options when a find or replace ran.
   *
   * @param matchCase Match Case
   * @param wholeWord Whole Word
   * @param regex Regex
   */
  record Options(boolean matchCase, boolean wholeWord, boolean regex) {}

  /**
   * A find in the document; playback never wraps, and no match fails the step.
   *
   * @param text the query, not empty
   * @param direction where the search starts
   * @param options the options
   */
  record Find(String text, Direction direction, Options options) implements MacroStep {
    /** Checks the fields. */
    public Find {
      if (text.isEmpty()) {
        throw new IllegalArgumentException("empty query");
      }
      Objects.requireNonNull(direction);
      Objects.requireNonNull(options);
    }
  }

  /**
   * The find bar's Replace, or Replace All with {@code all}.
   *
   * @param find the query, not empty
   * @param with the replacement
   * @param options the options
   * @param all true for Replace All
   */
  record Replace(String find, String with, Options options, boolean all) implements MacroStep {
    /** Checks the fields. */
    public Replace {
      if (find.isEmpty()) {
        throw new IllegalArgumentException("empty query");
      }
      Objects.requireNonNull(with);
      Objects.requireNonNull(options);
    }
  }

  /**
   * Go to Line's answer.
   *
   * @param line the line, from 1
   * @param column the column, from 1
   */
  record GoToLine(int line, int column) implements MacroStep {
    /** Checks the position. */
    public GoToLine {
      if (line < 1 || column < 1) {
        throw new IllegalArgumentException("position from 1");
      }
    }
  }

  /**
   * Tab Width > Other's answer.
   *
   * @param columns from 1
   */
  record TabWidth(int columns) implements MacroStep {
    /** Checks the width. */
    public TabWidth {
      if (columns < 1) {
        throw new IllegalArgumentException("tab width from 1");
      }
    }
  }

  /**
   * Insert Date and Time's format; playback reads the clock.
   *
   * @param pattern a {@link java.time.format.DateTimeFormatter} pattern
   */
  record DateTime(String pattern) implements MacroStep {
    /** Checks the pattern. */
    public DateTime {
      if (pattern.isEmpty()) {
        throw new IllegalArgumentException("empty pattern");
      }
    }
  }

  /**
   * Insert Number Sequence's values.
   *
   * @param sequence the values
   */
  record Sequence(NumberSequence sequence) implements MacroStep {
    /** Checks the values. */
    public Sequence {
      Objects.requireNonNull(sequence);
    }
  }
}
