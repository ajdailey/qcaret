package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.command.Keymap;
import io.github.ajdailey.qcaret.app.config.ConfigStore;
import io.github.ajdailey.qcaret.app.config.Preferences;
import io.github.ajdailey.qcaret.app.config.SettingsLocation;
import io.github.ajdailey.qcaret.app.config.SettingsMode;
import io.github.ajdailey.qcaret.app.log.LogFiles;
import io.github.ajdailey.qcaret.app.log.RecentLog;
import io.github.ajdailey.qcaret.app.macro.Macro;
import io.github.ajdailey.qcaret.app.macro.MacroFormat;
import io.github.ajdailey.qcaret.app.view.ManualUiDispatcher;
import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import java.nio.file.Path;
import java.util.SplittableRandom;
import java.util.function.LongSupplier;

/**
 * Macro playback workloads and the package-private entry points a playback benchmark needs: a
 * window over a {@link FakeView}, and a run started with its own turn clock.
 */
public final class MacroBench {
  private static final String[] WORDS = {
    "int", "return", "value", "count", "(", ")", "{", "}", ";", "=", "+", "if", "for", "name",
    "index", "final", "static", "while", "buffer", "offset", "length", "0", "1", "null", "new"
  };

  private static final String OFF = " case=off word=off regex=off";

  /** A recorded macro, the steps a user replays over every line. */
  public enum Workload {
    /** Typing at each line end, then Down. */
    TYPING(false, "command LINE_END", "type \" // ok\"", "command CARET_DOWN"),
    /** {@link #TYPING} with word wrap on. */
    TYPING_WRAP(true, "command LINE_END", "type \" // ok\"", "command CARET_DOWN"),
    /** Word and line moves with a typed char. */
    CARETS(
        false,
        "command LINE_START",
        "command WORD_RIGHT",
        "command WORD_RIGHT",
        "command SELECT_WORD_RIGHT",
        "type \"x\"",
        "command CARET_DOWN"),
    /** A find of each line's marker word, typed over. */
    FIND_TYPE(false, "find \"alpha\" forward" + OFF, "type \"ALPHA\""),
    /** A Replace step: the match replaced and the next one found. */
    REPLACE(false, "replace \"alpha\" \"omega\"" + OFF),
    /** Line commands: duplicate, upper case, indent, then past the copy. */
    COMMANDS(
        false,
        "command DUPLICATE",
        "command SELECT_LINE",
        "command UPPER_CASE",
        "command INDENT",
        "command CARET_DOWN",
        "command CARET_DOWN");

    private final boolean wrap;
    private final String text;

    Workload(boolean wrap, String... steps) {
      this.wrap = wrap;
      this.text = MacroFormat.HEADER + "\n" + String.join("\n", steps) + "\n";
    }

    /** Reports whether the run's view wraps. */
    public boolean wrap() {
      return wrap;
    }

    /**
     * Returns the macro.
     *
     * @return the parsed text
     */
    public Macro macro() {
      try {
        return MacroFormat.parse(text, name().toLowerCase(java.util.Locale.ROOT));
      } catch (MacroFormat.Refusal e) {
        throw new IllegalStateException(e);
      }
    }
  }

  /** A started run. */
  public interface Run {
    /** Reports whether the run ended. */
    boolean ended();

    /** Returns the end notice, or null. */
    String notice();

    /** Returns the iterations completed. */
    int iterations();
  }

  private MacroBench() {}

  /**
   * Generates source-like lines of 80 chars with LF, each holding the word {@code alpha} once.
   *
   * @param chars at least this many chars, whole lines
   * @param seed the random seed
   * @return the text
   */
  public static String text(int chars, long seed) {
    SplittableRandom random = new SplittableRandom(seed);
    StringBuilder out = new StringBuilder(chars + 80);
    StringBuilder line = new StringBuilder(80);
    while (out.length() < chars) {
      line.setLength(0);
      line.append("        ", 0, 4 * random.nextInt(3));
      int marker = 1 + random.nextInt(8);
      for (int w = 0; line.length() < 79; w++) {
        line.append(w == marker ? "alpha" : WORDS[random.nextInt(WORDS.length)]).append(' ');
      }
      if (line.indexOf("alpha") < 0 || line.indexOf("alpha") > 73) {
        line.replace(0, 6, "alpha ");
      }
      line.setLength(79);
      out.append(line).append('\n');
    }
    return out.toString();
  }

  /**
   * Creates a window over a {@link FakeView}.
   *
   * @param workers the workers
   * @param ui the window's dispatcher, which runs its tasks on {@code manual}'s thread
   * @param manual the view's dispatcher
   * @param settings the settings folder
   * @return the window
   */
  public static ApplicationController fakeWindow(
      Workers workers, UiDispatcher ui, ManualUiDispatcher manual, Path settings) {
    ApplicationController window =
        new ApplicationController(
            workers,
            ui,
            new FakeView(manual),
            new ConfigStore(new SettingsLocation(SettingsMode.EXPLICIT, settings)),
            Preferences.DEFAULTS,
            Harness.LIGHT_SYSTEM,
            MemoryBudget.fromRuntime(),
            Keymap.defaults(false),
            false);
    window.setLogging(new RecentLog(50, 200), LogFiles.NONE);
    return window;
  }

  /**
   * Turns word wrap on or off for the window's selected document; a fake view also needs its width.
   *
   * @param window the window
   * @param on true for wrap
   * @param columns the view's width in columns, or 0 to leave it to the view
   */
  public static void wrap(ApplicationController window, boolean on, int columns) {
    EditorController editor = window.documents().activeEditor();
    if (columns > 0) {
      editor.viewColumnsChanged(columns);
    }
    if (window.documents().wrap() != on) {
      window.execute(CommandId.WORD_WRAP);
    }
  }

  /**
   * Reports whether the selected editor's rows are installed and no worker command runs.
   *
   * @param window the window
   * @return true when settled
   */
  public static boolean settled(ApplicationController window) {
    EditorController editor = window.documents().activeEditor();
    return editor != null
        && !editor.rewrapping()
        && editor.busyCursor().active() == 0
        && window.documents().loading() == 0;
  }

  /**
   * Starts a macro in the window's selected editor.
   *
   * @param window the window
   * @param macro the macro
   * @param count the iterations, 0 to the end of the document
   * @param clock the turn budget's clock
   * @return the run
   * @throws IllegalStateException when refused
   */
  public static Run play(ApplicationController window, Macro macro, int count, LongSupplier clock) {
    final MacroPlayer player = window.windowSet().macros().play(window, macro, count, null);
    if (player == null) {
      throw new IllegalStateException("run refused");
    }
    player.setNanos(clock);
    return new Run() {
      @Override
      public boolean ended() {
        return player.ended();
      }

      @Override
      public String notice() {
        return player.notice();
      }

      @Override
      public int iterations() {
        return player.iterations();
      }
    };
  }
}
