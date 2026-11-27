package io.github.ajdailey.qcaret.app.controller;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.macro.Macro;
import io.github.ajdailey.qcaret.app.macro.MacroFormat;
import io.github.ajdailey.qcaret.core.document.BlockSelection;
import io.github.ajdailey.qcaret.core.document.Selection;
import io.github.ajdailey.qcaret.core.document.SelectionSet;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Live equals replay over generated input: commands through the editor's keys and the window, typed
 * brackets, quotes and surrogate pairs with auto-close and smart indent on, multiple carets and
 * block selections, worker commands, Cut/Copy/Paste and Find Next, recorded on one document and
 * replayed, after a save and load of the text format, on a copy with the same starting selections
 * and clipboard.
 */
class MacroRandomTest {
  private static final CommandId[] COMMANDS = {
    CommandId.CARET_LEFT,
    CommandId.CARET_RIGHT,
    CommandId.CARET_UP,
    CommandId.CARET_DOWN,
    CommandId.WORD_LEFT,
    CommandId.WORD_RIGHT,
    CommandId.LINE_START,
    CommandId.LINE_END,
    CommandId.DOCUMENT_START,
    CommandId.DOCUMENT_END,
    CommandId.PAGE_UP,
    CommandId.PAGE_DOWN,
    CommandId.SELECT_LEFT,
    CommandId.SELECT_RIGHT,
    CommandId.SELECT_UP,
    CommandId.SELECT_DOWN,
    CommandId.SELECT_WORD_RIGHT,
    CommandId.SELECT_LINE_END,
    CommandId.BLOCK_LEFT,
    CommandId.BLOCK_RIGHT,
    CommandId.BLOCK_UP,
    CommandId.BLOCK_DOWN,
    CommandId.INSERT_TAB,
    CommandId.NEWLINE,
    CommandId.DELETE_BACK,
    CommandId.DELETE_FORWARD,
    CommandId.DUPLICATE,
    CommandId.DELETE_LINE,
    CommandId.MOVE_LINES_UP,
    CommandId.MOVE_LINES_DOWN,
    CommandId.JOIN_LINES,
    CommandId.UPPER_CASE,
    CommandId.TITLE_CASE,
    CommandId.SWAP_CASE,
    CommandId.INDENT,
    CommandId.OUTDENT,
    CommandId.ADD_CARET_ABOVE,
    CommandId.ADD_CARET_BELOW,
    CommandId.SPLIT_INTO_LINES,
    CommandId.COLUMN_MODE,
    CommandId.CUT,
    CommandId.COPY,
    CommandId.PASTE,
    CommandId.DELETE_WORD_BACK,
    CommandId.DELETE_WORD_FORWARD,
    CommandId.TRANSPOSE,
    CommandId.CANCEL,
    CommandId.SELECT_ALL,
    CommandId.ADD_NEXT_OCCURRENCE,
    CommandId.SELECT_ALL_OCCURRENCES,
    CommandId.EXPAND_SELECTION,
    CommandId.SELECT_LINE,
    CommandId.UNDO_SELECTION,
    CommandId.REDO_SELECTION,
    CommandId.SORT_LINES_ASCENDING,
    CommandId.REVERSE_LINES,
    CommandId.TRIM_TRAILING_WHITESPACE,
    CommandId.GO_TO_MATCHING_BRACKET,
    CommandId.SELECT_TO_MATCHING_BRACKET,
    CommandId.TOGGLE_BOOKMARK,
    CommandId.NEXT_BOOKMARK
  };

  private static final String[] TYPED = {
    "a", "b", "x", " ", "{", "}", "(", ")", "[", "]", "\"", "'", "\u00e9", "\uD83D\uDE00"
  };

  private static final String[] WORDS = {"alpha", "beta", "foo", "(a)", "{", "}", "\"q\"", "x1"};

  private static final String CLIPBOARD = "clip\nboard";

  @TempDir Path temp;

  private MacroTesting t;
  private int steps;
  private int changed;

  @BeforeEach
  void create() {
    t = new MacroTesting(temp);
  }

  @AfterEach
  void close() throws InterruptedException {
    t.close();
  }

  @Test
  void generatedInputReplaysTheSame() throws Exception {
    for (int seed = 0; seed < 24; seed++) {
      round(seed, false);
    }
    assertTrue(steps > 24 * 20 && changed > 20, steps + " steps, " + changed + " changed");
  }

  @Test
  void generatedInputWithUndoReplaysTheSame() throws Exception {
    for (int seed = 100; seed < 112; seed++) {
      round(seed, true);
    }
  }

  private void round(int seed, boolean undo) throws Exception {
    Random random = new Random(seed);
    String start = startText(random);
    int starting = random.nextInt(3);
    EditorController a = t.open(start);
    select(a, starting);
    t.harness.view.clipboard = CLIPBOARD;
    t.app.execute(CommandId.RECORD_MACRO);
    for (int i = 0; i < 40; i++) {
      act(a, random, undo);
      t.settle(a);
    }
    t.app.execute(CommandId.RECORD_MACRO);
    Macro recorded = t.macros().recorded();
    String what = "seed " + seed + "\n" + MacroFormat.write(recorded);
    Macro loaded = MacroFormat.parse(MacroFormat.write(recorded), null);
    EditorController b = t.open(start);
    select(b, starting);
    t.harness.view.clipboard = CLIPBOARD;
    MacroPlayer run = t.play(loaded, 1);
    assertTrue(run.notice() != null && !run.notice().startsWith("Stopped"), what + run.notice());
    MacroTesting.assertSameState(a, b, what);
    steps += recorded.steps().size();
    changed += start.equals(MacroTesting.text(b)) ? 0 : 1;
  }

  private void act(EditorController editor, Random random, boolean undo) throws Exception {
    int kind = random.nextInt(20);
    if (kind < 6) {
      String typed = TYPED[random.nextInt(TYPED.length)];
      for (int i = 0; i < typed.length(); i++) {
        editor.type(typed.substring(i, i + 1));
      }
    } else if (kind == 6) {
      findNext(editor, random);
    } else if (kind == 7 && undo) {
      t.app.execute(random.nextBoolean() ? CommandId.UNDO : CommandId.REDO);
    } else {
      CommandId command = COMMANDS[random.nextInt(COMMANDS.length)];
      if (random.nextBoolean()) {
        editor.execute(command);
      } else {
        t.app.execute(command);
      }
    }
  }

  /** Finds a word that occurs after the primary selection, so the live find never wraps. */
  private void findNext(EditorController editor, Random random) throws InterruptedException {
    String text = MacroTesting.text(editor);
    int from = editor.selections().selections().primary().end();
    String word = WORDS[random.nextInt(3)];
    if (text.indexOf(word, from) < 0) {
      return;
    }
    t.find.close();
    t.find.setText(word);
    t.find.findNext();
  }

  private static String startText(Random random) {
    StringBuilder out = new StringBuilder();
    int lines = 3 + random.nextInt(8);
    for (int i = 0; i < lines; i++) {
      out.append(" ".repeat(random.nextInt(3) * 2));
      int words = 1 + random.nextInt(4);
      for (int w = 0; w < words; w++) {
        if (w > 0) {
          out.append(' ');
        }
        out.append(WORDS[random.nextInt(WORDS.length)]);
      }
      out.append('\n');
    }
    return out.toString();
  }

  /** Sets one caret, three carets or a block, from the same choice for both documents. */
  private static void select(EditorController editor, int choice) {
    int length = editor.document().text().length();
    switch (choice) {
      case 0 -> editor.selections().set(SelectionSet.caret(Math.min(3, length)));
      case 1 -> {
        List<Selection> carets = new ArrayList<Selection>();
        carets.add(Selection.caret(0));
        carets.add(Selection.of(Math.min(2, length), Math.min(4, length)));
        carets.add(Selection.caret(length));
        editor.selections().set(SelectionSet.of(carets, 1));
      }
      default ->
          editor
              .selections()
              .setBlock(new BlockSelection(0, 1, 2, 3), editor.document().tabWidth());
    }
  }
}
