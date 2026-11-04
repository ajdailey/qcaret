package io.github.ajdailey.qcaret.bench;

import io.github.ajdailey.qcaret.core.buffer.RopeStorage;
import io.github.ajdailey.qcaret.core.buffer.TextStorage;
import java.util.SplittableRandom;

/** The edit sequences of workloads W2 to W8, shared by the JMH benchmarks and the harness. */
public final class Workloads {
  /** Seed of every generated input and edit sequence. */
  public static final long SEED = 42;

  /** W3 edit count. */
  public static final int W3_EDITS = 100_000;

  /** W5 match count. */
  public static final int W5_MATCHES = 100_000;

  /** W4 caret count. */
  public static final int W4_CARETS = 1_000;

  private static final String INSERT_CHARS = "abcdefghijklmnop  (){};=\n";

  private Workloads() {}

  /**
   * One replacement.
   *
   * @param offset the first offset replaced
   * @param removeLength the chars removed
   * @param text the chars inserted
   */
  public record Edit(int offset, int removeLength, String text) {}

  /**
   * Generates W3: {@value #W3_EDITS} random inserts and deletes of 1-20 chars across a document of
   * {@code length} chars, half of each.
   *
   * @param length the document length before the first edit
   * @return the edits in order
   */
  public static Edit[] w3(int length) {
    SplittableRandom random = new SplittableRandom(SEED);
    Edit[] out = new Edit[W3_EDITS];
    int len = length;
    char[] buf = new char[20];
    for (int i = 0; i < W3_EDITS; i++) {
      int n = 1 + random.nextInt(20);
      if (random.nextBoolean() || len < n) {
        for (int j = 0; j < n; j++) {
          buf[j] = INSERT_CHARS.charAt(random.nextInt(INSERT_CHARS.length()));
        }
        out[i] = new Edit(random.nextInt(len + 1), 0, new String(buf, 0, n));
        len += n;
      } else {
        out[i] = new Edit(random.nextInt(len - n + 1), n, "");
        len -= n;
      }
    }
    return out;
  }

  /**
   * Builds a rope from a corpus and applies W3.
   *
   * @param corpus the corpus
   * @param sizeMiB the corpus size
   * @return the storage after W3
   */
  public static TextStorage afterW3(Corpus corpus, int sizeMiB) {
    String text = corpus.generate(sizeMiB * Corpus.MIB, SEED);
    TextStorage storage = RopeStorage.of(text);
    apply(storage, w3(text.length()), null);
    return storage;
  }

  /**
   * Applies edits in order.
   *
   * @param storage the storage
   * @param edits the edits
   * @param removed receives each edit's removed text when not null
   */
  public static void apply(TextStorage storage, Edit[] edits, String[] removed) {
    for (int i = 0; i < edits.length; i++) {
      Edit e = edits[i];
      if (removed != null) {
        removed[i] = storage.subSequence(e.offset, e.offset + e.removeLength).toString();
      }
      storage.replace(e.offset, e.removeLength, e.text);
    }
  }

  /**
   * W8: undoes {@code edits} by applying their inverses in reverse order, then redoes them.
   *
   * @param storage the storage after {@code edits}
   * @param edits the edits
   * @param removed the removed text {@link #apply} recorded
   */
  public static void undoRedo(TextStorage storage, Edit[] edits, String[] removed) {
    for (int i = edits.length - 1; i >= 0; i--) {
      Edit e = edits[i];
      storage.replace(e.offset, e.text.length(), removed[i]);
    }
    apply(storage, edits, null);
  }

  /**
   * W5: replaces {@value #W5_MATCHES} evenly spaced 4-char matches with 6 chars as one batch,
   * highest offset first.
   *
   * @param storage the storage
   */
  public static void replaceAll(TextStorage storage) {
    int step = storage.length() / W5_MATCHES;
    for (int i = W5_MATCHES - 1; i >= 0; i--) {
      storage.replace(i * step, 4, "REPLAC");
    }
  }

  /**
   * Returns {@value #W4_CARETS} caret offsets spread evenly over {@code length} chars.
   *
   * @param length the document length
   * @return the ascending offsets
   */
  public static int[] carets(int length) {
    int[] out = new int[W4_CARETS];
    for (int i = 0; i < W4_CARETS; i++) {
      out[i] = (int) ((long) length * i / W4_CARETS);
    }
    return out;
  }

  /**
   * W4: types one char at every caret as one batch, highest offset first.
   *
   * @param storage the storage
   * @param carets the caret offsets before the first keystroke
   * @param keystroke the number of keystrokes already typed
   */
  public static void multiCaretKeystroke(TextStorage storage, int[] carets, int keystroke) {
    for (int i = carets.length - 1; i >= 0; i--) {
      storage.replace(carets[i] + keystroke * (i + 1), 0, "x");
    }
  }

  /** W2: typing at an advancing caret from the middle of the document, 10% backspaces. */
  public static final class Typing {
    private final SplittableRandom random = new SplittableRandom(SEED);
    private final int start;
    private int caret;

    /**
     * Starts typing at the middle of a document.
     *
     * @param length the document length
     */
    public Typing(int length) {
      start = length / 2;
      caret = start;
    }

    /**
     * Applies one keystroke.
     *
     * @param storage the storage
     */
    public void keystroke(TextStorage storage) {
      if (caret > start && random.nextInt(10) == 0) {
        storage.replace(caret - 1, 1, "");
        caret--;
      } else {
        storage.replace(caret, 0, "x");
        caret++;
      }
    }
  }
}
