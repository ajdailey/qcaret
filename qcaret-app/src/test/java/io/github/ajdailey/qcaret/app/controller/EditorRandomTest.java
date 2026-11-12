package io.github.ajdailey.qcaret.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.editor.DisplayProjection;
import io.github.ajdailey.qcaret.app.view.ManualUiDispatcher;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.document.BlockSelection;
import io.github.ajdailey.qcaret.core.document.Document;
import io.github.ajdailey.qcaret.core.document.DocumentChange;
import io.github.ajdailey.qcaret.core.document.DocumentListener;
import io.github.ajdailey.qcaret.core.document.Replacement;
import io.github.ajdailey.qcaret.core.document.Selection;
import io.github.ajdailey.qcaret.core.document.SelectionModel;
import io.github.ajdailey.qcaret.core.document.SelectionSet;
import io.github.ajdailey.qcaret.core.document.TextEdit;
import io.github.ajdailey.qcaret.core.document.UndoLimits;
import io.github.ajdailey.qcaret.core.edit.Edit;
import io.github.ajdailey.qcaret.core.edit.ParagraphWrap;
import io.github.ajdailey.qcaret.core.syntax.Grammar;
import io.github.ajdailey.qcaret.core.text.LineEnding;
import io.github.ajdailey.qcaret.core.text.LineEndingStats;
import io.github.ajdailey.qcaret.core.text.TextEncoding;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Random editing commands through the controller on text with mixed line endings, surrogate pairs
 * and combining marks, wrapped on every third seed, with block selections, compositions, Wrap
 * Paragraph and a second view, checked after every step: the change events replay to the text, no
 * selection sits inside a CR LF or a surrogate pair, no surrogate is split, line ending counts and
 * display rows match a fresh count, no line command, Wrap Paragraph, Transpose or case command
 * joins a CR and an LF, and undo and redo walk the whole history. Seeds and the coalescing clock
 * are fixed. {@code -Dqcaret.editor.seed=N} runs one seed, {@code -Dqcaret.editor.seeds=N} the
 * first N; a failure names its seed and step.
 */
class EditorRandomTest {
  private static final int SEEDS = 1000;
  private static final int STEPS = 300;
  private static final long STEP_NANOS = 400_000_000L;

  private static final String[] TOKENS = {
    "a",
    "b",
    "foo",
    "bar",
    " ",
    "  ",
    "\t",
    "\n",
    "\r\n",
    "\r",
    "中",
    "😀",
    "(",
    ")",
    "{",
    "}",
    "\"",
    "x1",
    "é",
    "e\u0301",
    "\u0301",
    "\u0301\u0301",
    "Ab",
    "'",
    "\ud83d\udc4d\ud83c\udffd"
  };
  private static final CommandId[] COMMANDS = {
    CommandId.UNDO,
    CommandId.UNDO,
    CommandId.REDO,
    CommandId.CUT,
    CommandId.COPY,
    CommandId.PASTE,
    CommandId.NEWLINE,
    CommandId.DELETE_BACK,
    CommandId.DELETE_BACK,
    CommandId.DELETE_FORWARD,
    CommandId.DELETE_FORWARD,
    CommandId.CARET_LEFT,
    CommandId.CARET_RIGHT,
    CommandId.CARET_UP,
    CommandId.CARET_DOWN,
    CommandId.SELECT_LEFT,
    CommandId.SELECT_RIGHT,
    CommandId.SELECT_UP,
    CommandId.SELECT_DOWN,
    CommandId.WORD_LEFT,
    CommandId.WORD_RIGHT,
    CommandId.SELECT_WORD_LEFT,
    CommandId.SELECT_WORD_RIGHT,
    CommandId.LINE_START,
    CommandId.LINE_END,
    CommandId.PAGE_UP,
    CommandId.PAGE_DOWN,
    CommandId.DOCUMENT_START,
    CommandId.DOCUMENT_END,
    CommandId.BLOCK_LEFT,
    CommandId.BLOCK_RIGHT,
    CommandId.BLOCK_UP,
    CommandId.BLOCK_DOWN,
    CommandId.INSERT_TAB,
    CommandId.INDENT,
    CommandId.OUTDENT,
    CommandId.DUPLICATE,
    CommandId.DELETE_LINE,
    CommandId.MOVE_LINES_UP,
    CommandId.MOVE_LINES_DOWN,
    CommandId.JOIN_LINES,
    CommandId.UPPER_CASE,
    CommandId.LOWER_CASE,
    CommandId.ADD_CARET_ABOVE,
    CommandId.ADD_CARET_BELOW,
    CommandId.SPLIT_INTO_LINES,
    CommandId.TOGGLE_LINE_COMMENT,
    CommandId.TOGGLE_BLOCK_COMMENT,
    CommandId.SELECT_ALL,
    CommandId.CANCEL,
    CommandId.DELETE_WORD_BACK,
    CommandId.DELETE_WORD_FORWARD,
    CommandId.TITLE_CASE,
    CommandId.SWAP_CASE,
    CommandId.TRANSPOSE,
    CommandId.TRANSPOSE,
    CommandId.SELECT_ALL_OCCURRENCES,
    CommandId.SKIP_OCCURRENCE,
    CommandId.ADD_NEXT_OCCURRENCE,
    CommandId.EXPAND_SELECTION,
    CommandId.SELECT_LINE,
    CommandId.UNDO_SELECTION,
    CommandId.REDO_SELECTION
  };
  private static final CommandId[] TRANSFORMS = {
    CommandId.SORT_LINES_ASCENDING,
    CommandId.SORT_LINES_DESCENDING,
    CommandId.SORT_LINES_IGNORE_CASE,
    CommandId.REMOVE_DUPLICATE_LINES,
    CommandId.TRIM_TRAILING_WHITESPACE
  };

  private final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
  private Workers workers;

  static List<Long> seeds() {
    List<Long> seeds = new ArrayList<Long>();
    String one = System.getProperty("qcaret.editor.seed");
    if (one != null) {
      seeds.add(Long.parseLong(one));
    } else {
      long count = Long.getLong("qcaret.editor.seeds", SEEDS);
      for (long s = 1; s <= count; s++) {
        seeds.add(s);
      }
    }
    return seeds;
  }

  /** Replays every change on a mirror; a removed text that differs fails the step. */
  private static final class Mirror implements DocumentListener {
    final StringBuilder text;

    Mirror(String initial) {
      text = new StringBuilder(initial);
    }

    @Override
    public void textChanged(DocumentChange change) {
      List<TextEdit> edits = change.edits();
      for (int i = edits.size() - 1; i >= 0; i--) {
        TextEdit e = edits.get(i);
        int end = e.offset() + e.removed().length();
        assertEquals(e.removed(), text.substring(e.offset(), end), "removed text");
        text.replace(e.offset(), end, e.inserted());
      }
    }

    @Override
    public void dirtyChanged(boolean dirty) {
      // Not checked here.
    }
  }

  @BeforeEach
  void setUp() {
    workers =
        new Workers(
            1,
            new Thread.UncaughtExceptionHandler() {
              @Override
              public void uncaughtException(Thread thread, Throwable error) {
                failure.set(error);
              }
            });
  }

  @AfterEach
  void tearDown() {
    workers.close();
    assertEquals(null, failure.get());
  }

  @ParameterizedTest(name = "seed={0}")
  @MethodSource("seeds")
  void randomCommandsKeepEveryInvariant(long seed) {
    SplittableRandom random = new SplittableRandom(seed);
    ManualUiDispatcher ui = new ManualUiDispatcher();
    FakeView view = new FakeView(ui);
    final long[] now = {0};
    String initial = text(random, seed % 2 == 0 ? random.nextInt(8) : random.nextInt(120));
    Document document =
        Document.of(
            initial,
            TextEncoding.of(StandardCharsets.UTF_8, false),
            LineEnding.values()[random.nextInt(LineEnding.values().length)],
            UndoLimits.DEFAULTS,
            new LongSupplier() {
              @Override
              public long getAsLong() {
                return now[0];
              }
            });
    Mirror mirror = new Mirror(initial);
    document.addListener(mirror);
    SelectionModel selections = document.newSelectionModel();
    SelectionModel other = document.newSelectionModel();
    int wrap = seed % 3 == 0 ? 8 + random.nextInt(20) : 0;
    DisplayProjection projection =
        new DisplayProjection(
            document, DisplayProjection.layout(document.text(), wrap, new CancellationToken()));
    EditorController editor =
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
    editor.attach((FakeView.FakeTab) view.addTab(projection, selections, editor, null, 0));
    editor.setComments(new Grammar.Comments("//", "/*", "*/"));
    for (int step = 0; step < STEPS; step++) {
      now[0] += random.nextLong(STEP_NANOS);
      String where = "seed " + seed + " step " + step;
      if (random.nextInt(10) == 0) {
        int lines = document.text().lineCount();
        other.setBlock(
            new BlockSelection(
                random.nextInt(lines), random.nextInt(9), random.nextInt(lines), random.nextInt(9)),
            document.tabWidth());
      }
      act(random, document, selections, editor, where);
      ui.runPending();
      check(document, mirror, selections, projection, wrap, where);
      check(document, other, where);
    }
    document.clearPreedit();
    String last = document.text().toString();
    int undone = 0;
    while (document.canUndo()) {
      document.undo(selections);
      undone++;
    }
    assertEquals(initial, document.text().toString(), "undo all, seed " + seed);
    for (int i = 0; i < undone; i++) {
      document.redo(selections);
    }
    assertEquals(last, document.text().toString(), "redo all, seed " + seed);
    projection.dispose();
  }

  private static void act(
      SplittableRandom random,
      Document document,
      SelectionModel selections,
      EditorController editor,
      String where) {
    int choice = random.nextInt(100);
    if (choice < 12) {
      selections.set(selectionsOf(random, document.text()));
    } else if (choice < 18) {
      int lines = document.text().lineCount();
      int a = random.nextInt(lines);
      int b = Math.min(lines - 1, a + random.nextInt(5));
      int c = random.nextInt(14);
      int d = random.nextBoolean() ? c : random.nextInt(14);
      selections.setBlock(new BlockSelection(a, c, b, d), document.tabWidth());
    } else if (choice < 36) {
      String typed = TOKENS[random.nextInt(TOKENS.length)];
      // Every platform sends typed and committed text one char at a time.
      for (char c : typed.toCharArray()) {
        editor.type(c == '\n' || c == '\r' ? "z" : String.valueOf(c));
      }
    } else if (choice < 37) {
      document.clearPreedit();
      Edit edit =
          ParagraphWrap.wrap(
              document.text(),
              selections.selections(),
              1 + random.nextInt(12),
              random.nextBoolean() ? "//" : null,
              document.tabWidth(),
              document.lineEnding().text(),
              new CancellationToken());
      if (edit != null) {
        assertFalse(joins(document.text().toString(), edit), "joined breaks, wrap, " + where);
        document.apply(edit.batch(), selections, edit.after());
      }
    } else if (choice < 38) {
      String token = TOKENS[random.nextInt(TOKENS.length)];
      editor.insertText(token.replace('\n', 'z').replace('\r', 'z'));
    } else if (choice < 40) {
      document.clearPreedit();
      CommandId command = TRANSFORMS[random.nextInt(TRANSFORMS.length)];
      long pairs = document.lineEndings().crLf();
      int lines = document.text().lineCount();
      Edit edit =
          EditorController.lineEdit(
              command, document.text(), selections.selections(), new CancellationToken());
      if (edit != null) {
        document.apply(edit.batch(), selections, edit.after());
      }
      assertTrue(document.lineEndings().crLf() <= pairs, "joined breaks, " + where);
      if (command != CommandId.REMOVE_DUPLICATE_LINES) {
        assertEquals(lines, document.text().lineCount(), "line count, " + where);
      }
    } else if (choice < 48) {
      compose(random, document, selections, editor, where);
    } else {
      CommandId command = COMMANDS[random.nextInt(COMMANDS.length)];
      long pairs = document.lineEndings().crLf();
      editor.execute(command);
      if (command == CommandId.DELETE_LINE
          || command == CommandId.MOVE_LINES_UP
          || command == CommandId.MOVE_LINES_DOWN
          || command == CommandId.TRANSPOSE
          || command == CommandId.TITLE_CASE
          || command == CommandId.SWAP_CASE) {
        assertTrue(document.lineEndings().crLf() <= pairs, "joined breaks, " + where);
      }
    }
  }

  /** Runs a composition to a cancel, a commit, a clear by the model, or leaves it showing. */
  private static void compose(
      SplittableRandom random,
      Document document,
      SelectionModel selections,
      EditorController editor,
      String where) {
    document.clearPreedit();
    String before = document.text().toString();
    boolean dirty = document.isDirty();
    SelectionSet set = selections.selections();
    editor.preeditFromView("ab");
    editor.preeditFromView("abc");
    switch (random.nextInt(4)) {
      case 0 -> {
        editor.preeditFromView("");
        assertEquals(before, document.text().toString(), "canceled, " + where);
        assertEquals(dirty, document.isDirty(), "dirty, " + where);
        assertEquals(set.selections().size(), selections.selections().size(), "kept, " + where);
      }
      case 1 -> {
        editor.preeditFromView("");
        editor.type("a");
        editor.type("b");
      }
      case 2 -> {
        document.clearPreedit();
        editor.preeditFromView("abcd");
        editor.preeditFromView("");
        assertEquals(before, document.text().toString(), "cleared, " + where);
      }
      default -> assertFalse(document.preeditRange() == null, "showing, " + where);
    }
  }

  private static void check(
      Document document,
      Mirror mirror,
      SelectionModel selections,
      DisplayProjection projection,
      int wrap,
      String where) {
    String text = document.text().toString();
    assertEquals(text, mirror.text.toString(), "mirror, " + where);
    check(document, selections, where);
    LineEndingStats stats = LineEndingStats.count(text, 0, text.length());
    assertEquals(stats.lf(), document.lineEndings().lf(), "LF, " + where);
    assertEquals(stats.crLf(), document.lineEndings().crLf(), "CR LF, " + where);
    assertEquals(stats.cr(), document.lineEndings().cr(), "CR, " + where);
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (Character.isHighSurrogate(c)) {
        assertTrue(
            i + 1 < text.length() && Character.isLowSurrogate(text.charAt(i + 1)),
            "lone high surrogate, " + where);
      } else if (Character.isLowSurrogate(c)) {
        assertTrue(
            i > 0 && Character.isHighSurrogate(text.charAt(i - 1)), "lone low surrogate, " + where);
      }
    }
    DisplayProjection fresh =
        new DisplayProjection(
            document, DisplayProjection.layout(document.text(), wrap, new CancellationToken()));
    try {
      assertEquals(fresh.rowCount(), projection.rowCount(), "rows, " + where);
      assertEquals(fresh.length(), projection.length(), "display length, " + where);
      for (int r = 0; r < fresh.rowCount(); r++) {
        assertEquals(fresh.rowStart(r), projection.rowStart(r), "row start, " + where);
        assertEquals(fresh.rowText(r), projection.rowText(r), "row text, " + where);
      }
    } finally {
      fresh.dispose();
    }
  }

  private static void check(Document document, SelectionModel model, String where) {
    CharSequence text = document.text();
    int previous = -1;
    boolean range = false;
    for (Selection s : model.selections().selections()) {
      assertTrue(
          s.start() > previous || (s.start() == previous && range && !s.isEmpty()),
          "sorted and disjoint, " + where);
      range = !s.isEmpty();
      assertTrue(s.end() <= text.length(), "bounds, " + where);
      assertTrue(legal(text, s.anchor()) && legal(text, s.caret()), "inside a pair, " + where);
      previous = s.end();
    }
    BlockSelection block = model.block();
    if (block != null) {
      assertTrue(
          Math.max(block.anchorLine(), block.caretLine()) < document.text().lineCount(),
          "block lines, " + where);
    }
  }

  /** Reports whether a replacement puts a CR directly before an LF at one of its edges. */
  private static boolean joins(String text, Edit edit) {
    for (Replacement r : edit.batch().replacements()) {
      String in = r.text();
      char before = r.offset() > 0 ? text.charAt(r.offset() - 1) : 0;
      char after = r.end() < text.length() ? text.charAt(r.end()) : 0;
      char first = in.isEmpty() ? after : in.charAt(0);
      char last = in.isEmpty() ? before : in.charAt(in.length() - 1);
      if ((before == '\r' && first == '\n') || (last == '\r' && after == '\n')) {
        return true;
      }
    }
    return false;
  }

  private static String text(SplittableRandom random, int tokens) {
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < tokens; i++) {
      out.append(TOKENS[random.nextInt(TOKENS.length)]);
    }
    return out.toString();
  }

  private static boolean legal(CharSequence text, int at) {
    if (at <= 0 || at >= text.length()) {
      return true;
    }
    char before = text.charAt(at - 1);
    char after = text.charAt(at);
    return !(before == '\r' && after == '\n')
        && !(Character.isHighSurrogate(before) && Character.isLowSurrogate(after));
  }

  private static SelectionSet selectionsOf(SplittableRandom random, CharSequence text) {
    int count = 1 + random.nextInt(4);
    List<Selection> list = new ArrayList<Selection>();
    for (int i = 0; i < count; i++) {
      int a = legalized(text, random.nextInt(text.length() + 1));
      int b = a;
      if (random.nextInt(3) == 0) {
        b = legalized(text, Math.min(text.length(), a + random.nextInt(12)));
      }
      list.add(Selection.of(a, b));
    }
    return SelectionSet.of(list, random.nextInt(count));
  }

  private static int legalized(CharSequence text, int at) {
    return legal(text, at) ? at : at - 1;
  }
}
