package io.github.ajdailey.qcaret.core.document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.core.text.LineEnding;
import io.github.ajdailey.qcaret.core.text.LineEndingStats;
import io.github.ajdailey.qcaret.core.text.TextEncoding;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.function.LongSupplier;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Random batches, undo, redo, groups, saves and selection moves on a {@link Document}, checked
 * after every step: text integrity against a replay of the change events, position mapping against
 * a step-by-step reference, undo and redo reversibility (an undo state always holds the same text),
 * selection set invariants, revision consistency and line ending counts. {@code
 * -Dqcaret.document.seed=N} runs one seed; a failure names its seed.
 */
class DocumentRandomTest {
  private static final int SEEDS = 24;
  private static final int OPS = 1_500;
  private static final String ALPHABET = "abcdef  \t\n\r\n\ré中";

  static List<Long> seeds() {
    List<Long> seeds = new ArrayList<Long>();
    String one = System.getProperty("qcaret.document.seed");
    if (one != null) {
      seeds.add(Long.parseLong(one));
    } else {
      for (long s = 1; s <= SEEDS; s++) {
        seeds.add(s);
      }
    }
    return seeds;
  }

  /** An offset tracked by applying each event's edits one at a time, highest first. */
  private static final class Tracked {
    final long revision;
    final int original;
    final Bias bias;
    final Anchor anchor;
    int expected;

    Tracked(long revision, int offset, Bias bias, Anchor anchor) {
      this.revision = revision;
      this.original = offset;
      this.bias = bias;
      this.anchor = anchor;
      this.expected = offset;
    }
  }

  /** Replays every change on a mirror and checks revisions and line numbers. */
  private static final class Replay implements DocumentListener {
    final StringBuilder mirror;
    final List<Tracked> tracked;
    long revision;

    Replay(String text, List<Tracked> tracked) {
      this.mirror = new StringBuilder(text);
      this.tracked = tracked;
    }

    @Override
    public void textChanged(DocumentChange change) {
      assertEquals(revision + 1, change.revision(), "revision step");
      revision = change.revision();
      List<TextEdit> edits = change.edits();
      for (int i = 1; i < edits.size(); i++) {
        TextEdit a = edits.get(i - 1);
        assertTrue(a.offset() + a.removed().length() <= edits.get(i).offset(), "edit order");
      }
      for (int i = edits.size() - 1; i >= 0; i--) {
        TextEdit e = edits.get(i);
        int end = e.offset() + e.removed().length();
        assertEquals(e.removed(), mirror.substring(e.offset(), end), "removed text");
        assertEquals(lineOf(mirror, e.offset()), e.startLine(), "start line");
        mirror.replace(e.offset(), end, e.inserted());
        for (Tracked t : tracked) {
          t.expected = map(t.expected, e, t.bias);
        }
      }
    }

    @Override
    public void dirtyChanged(boolean dirty) {
      // Checked against the saved text after each step.
    }

    private static int map(int p, TextEdit e, Bias bias) {
      int o = e.offset();
      int r = e.removed().length();
      int n = e.inserted().length();
      if (p < o) {
        return p;
      }
      if (p > o + r || (p == o + r && r > 0)) {
        return p + n - r;
      }
      return bias == Bias.BEFORE ? o : o + n;
    }

    private static int lineOf(CharSequence s, int offset) {
      int line = 0;
      for (int i = 0; i < offset; i++) {
        char c = s.charAt(i);
        if (c == '\n' || (c == '\r' && (i + 1 >= s.length() || s.charAt(i + 1) != '\n'))) {
          line++;
        }
      }
      return line;
    }
  }

  @ParameterizedTest(name = "seed={0}")
  @MethodSource("seeds")
  void randomStepsKeepEveryInvariant(long seed) {
    final SplittableRandom random = new SplittableRandom(seed);
    final long[] now = {0};
    LongSupplier clock =
        new LongSupplier() {
          @Override
          public long getAsLong() {
            return now[0];
          }
        };
    UndoLimits limits = seed % 3 == 0 ? new UndoLimits(40, 4_000) : UndoLimits.DEFAULTS;
    String initial = text(random, random.nextInt(3_000));
    Document doc =
        new Document(
            initial, TextEncoding.of(StandardCharsets.UTF_8, false), LineEnding.LF, limits, clock);
    List<Tracked> tracked = new ArrayList<Tracked>();
    Replay replay = new Replay(initial, tracked);
    doc.addListener(replay);
    SelectionModel[] models = {doc.newSelectionModel(), doc.newSelectionModel()};
    Map<Long, String> textOfState = new HashMap<Long, String>();
    String saved = initial;
    for (int op = 0; op < OPS; op++) {
      now[0] += random.nextLong(1_500_000_000L);
      int choice = random.nextInt(100);
      SelectionModel origin = models[random.nextInt(2)];
      if (choice < 40) {
        doc.apply(batch(random, doc.text().length(), doc.text()), origin, null);
      } else if (choice < 55) {
        typingRun(random, doc, origin, now);
      } else if (choice < 65) {
        doc.beginGroup(origin);
        for (int k = random.nextInt(3); k >= 0; k--) {
          doc.apply(batch(random, doc.text().length(), doc.text()), origin, null);
        }
        doc.endGroup();
      } else if (choice < 78) {
        doc.undo(origin);
      } else if (choice < 88) {
        doc.redo(origin);
      } else if (choice < 93) {
        origin.set(randomSelections(random, doc.text().length()));
      } else if (choice < 96) {
        doc.markSaved(doc.snapshot());
        saved = doc.text().toString();
      } else {
        int offset = random.nextInt(doc.text().length() + 1);
        Bias bias = random.nextBoolean() ? Bias.BEFORE : Bias.AFTER;
        tracked.add(new Tracked(doc.revision(), offset, bias, doc.anchor(offset, bias)));
      }
      check(doc, replay, models, textOfState, saved, seed, op);
    }
    String last = doc.text().toString();
    int undone = 0;
    while (doc.canUndo()) {
      doc.undo(models[0]);
      undone++;
    }
    if (limits == UndoLimits.DEFAULTS) {
      assertEquals(initial, doc.text().toString(), "undo all, seed " + seed);
    }
    for (int i = 0; i < undone; i++) {
      doc.redo(models[0]);
    }
    assertEquals(last, doc.text().toString(), "redo all, seed " + seed);
    check(doc, replay, models, textOfState, saved, seed, OPS);
  }

  private static void check(
      Document doc,
      Replay replay,
      SelectionModel[] models,
      Map<Long, String> textOfState,
      String saved,
      long seed,
      int op) {
    String where = "seed " + seed + " op " + op;
    String text = doc.text().toString();
    assertEquals(replay.mirror.toString(), text, "text, " + where);
    assertEquals(replay.revision, doc.revision(), "revision, " + where);
    DocumentSnapshot snapshot = doc.snapshot();
    String before = textOfState.putIfAbsent(snapshot.state(), text);
    if (before != null) {
      assertEquals(before, text, "undo state text, " + where);
    }
    if (!doc.isDirty()) {
      assertEquals(saved, text, "clean text, " + where);
    }
    assertEquals(
        LineEndingStats.count(text, 0, text.length()).lf(), doc.lineEndings().lf(), "LF " + where);
    assertEquals(
        LineEndingStats.count(text, 0, text.length()).crLf(),
        doc.lineEndings().crLf(),
        "CR LF " + where);
    assertEquals(
        LineEndingStats.count(text, 0, text.length()).cr(), doc.lineEndings().cr(), "CR " + where);
    for (Tracked t : replay.tracked) {
      assertEquals(t.expected, t.anchor.offset(), "anchor, " + where);
      int mapped = doc.positions().map(t.revision, t.original, t.bias);
      if (mapped != PositionMap.UNMAPPABLE) {
        assertEquals(t.expected, mapped, "position map, " + where);
      }
    }
    for (SelectionModel model : models) {
      SelectionSet set = model.selections();
      List<Selection> list = set.selections();
      assertTrue(set.primaryIndex() >= 0 && set.primaryIndex() < list.size(), "primary " + where);
      for (int i = 0; i < list.size(); i++) {
        Selection s = list.get(i);
        assertTrue(s.start() >= 0 && s.end() <= text.length(), "bounds, " + where);
        if (i > 0) {
          assertTrue(list.get(i - 1).end() < s.start(), "disjoint and sorted, " + where);
        }
      }
    }
  }

  /** Types or deletes 1 to 6 chars at one caret, 100 ms apart, so consecutive keys coalesce. */
  private static void typingRun(
      SplittableRandom random, Document doc, SelectionModel origin, long[] now) {
    int caret = random.nextInt(doc.text().length() + 1);
    EditBatch.Kind kind = EditBatch.Kind.values()[1 + random.nextInt(3)];
    for (int k = 1 + random.nextInt(6); k > 0; k--) {
      int length = doc.text().length();
      Replacement r;
      if (kind == EditBatch.Kind.TYPING) {
        r = new Replacement(caret, 0, text(random, 1));
      } else if (kind == EditBatch.Kind.BACKSPACE && caret > 0) {
        r = new Replacement(caret - 1, 1, "");
      } else if (kind == EditBatch.Kind.DELETE && caret < length) {
        r = new Replacement(caret, 1, "");
      } else {
        return;
      }
      caret = kind == EditBatch.Kind.TYPING ? caret + 1 : r.offset();
      doc.apply(EditBatch.of(kind, List.of(r)), origin, SelectionSet.caret(caret));
      now[0] += 100_000_000L;
    }
  }

  private static EditBatch batch(SplittableRandom random, int length, CharSequence text) {
    EditBatch.Kind kind = EditBatch.Kind.values()[random.nextInt(4)];
    int count = 1 + random.nextInt(4);
    List<Replacement> out = new ArrayList<Replacement>();
    int floor = 0;
    for (int i = 0; i < count && floor <= length; i++) {
      int offset = floor + random.nextInt(Math.max(1, (length - floor) / (count - i) + 1));
      if (offset > length) {
        break;
      }
      int remove = random.nextInt(3) == 0 ? 0 : random.nextInt(Math.min(length - offset, 12) + 1);
      String insert = random.nextInt(3) == 0 ? "" : text(random, 1 + random.nextInt(8));
      if (kind == EditBatch.Kind.TYPING) {
        insert = text(random, 1);
      }
      out.add(new Replacement(offset, remove, insert));
      floor = offset + remove + 1;
    }
    return EditBatch.of(kind, out);
  }

  private static SelectionSet randomSelections(SplittableRandom random, int length) {
    int count = 1 + random.nextInt(5);
    List<Selection> out = new ArrayList<Selection>();
    for (int i = 0; i < count; i++) {
      out.add(Selection.of(random.nextInt(length + 1), random.nextInt(length + 1)));
    }
    return SelectionSet.of(out, random.nextInt(count));
  }

  private static String text(SplittableRandom random, int length) {
    char[] out = new char[length];
    for (int i = 0; i < length; i++) {
      out[i] = ALPHABET.charAt(random.nextInt(ALPHABET.length()));
    }
    return new String(out);
  }
}
