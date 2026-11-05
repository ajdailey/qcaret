package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.editor.FoldRegionSource;
import io.github.ajdailey.qcaret.app.editor.TokenCache;
import io.github.ajdailey.qcaret.app.editor.TokenSource;
import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import io.github.ajdailey.qcaret.core.buffer.TextSnapshot;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.WorkerThread;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.document.Bias;
import io.github.ajdailey.qcaret.core.document.Document;
import io.github.ajdailey.qcaret.core.document.DocumentChange;
import io.github.ajdailey.qcaret.core.document.DocumentListener;
import io.github.ajdailey.qcaret.core.document.PositionMap;
import io.github.ajdailey.qcaret.core.document.TextEdit;
import io.github.ajdailey.qcaret.core.syntax.Syntax;
import io.github.ajdailey.qcaret.core.syntax.SyntaxEngine;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Highlights one document for every view of it. The UI thread keeps the painted tokens in a {@link
 * TokenCache}; a {@link SyntaxEngine} on a {@code qcaret-cpu-} worker computes them. The UI thread
 * runs no regex.
 *
 * <ul>
 *   <li>The UI thread posts each change, syntax switch, view range and request for missing lines to
 *       a queue. One task at a time drains it on the CPU pool, then runs the engine for at most
 *       {@value #SLICE_MILLIS} ms, posts the output and submits itself again while work remains, so
 *       other tasks share the pool.
 *   <li>Each output carries the switch generation and the document revision the engine read. An
 *       output of an older generation is dropped. Its tokens apply only at the current revision;
 *       the lines the frontier tokenized are dropped from the cache at any revision, mapped through
 *       the {@link PositionMap}, so no stale entry outlives an edit the engine has seen.
 *   <li>A change shifts the cached tokens at once, so typing does not flash plain text. An output
 *       repaints the views only when it changes a line one of them shows.
 *   <li>The fold regions of the last complete pass are kept for its revision; {@link #regions} may
 *       be called on any thread.
 *   <li>Lines over the length limit stay plain; their count is logged once per complete pass when
 *       it changes.
 * </ul>
 */
final class SyntaxHighlighter implements TokenSource, FoldRegionSource, DocumentListener {
  /** Longest run of the engine per task. */
  static final long SLICE_MILLIS = 10;

  private static final Logger LOG = LoggerFactory.getLogger(SyntaxHighlighter.class);
  private static final long SLICE_NANOS = TimeUnit.MILLISECONDS.toNanos(SLICE_MILLIS);

  private final Document document;
  private final Workers workers;
  private final UiDispatcher ui;
  private final Runnable repaint;
  private final int maxLineChars;
  private final TokenCache cache = new TokenCache(TokenCache.DEFAULT_CAPACITY);
  private final Map<Object, int[]> views = new IdentityHashMap<Object, int[]>();
  private final Map<Object, Integer> keys = new IdentityHashMap<Object, Integer>();
  private final CancellationToken cancel = new CancellationToken();
  private final Queue<Message> inbox = new ConcurrentLinkedQueue<Message>();
  private final AtomicBoolean scheduled = new AtomicBoolean();
  private TextSnapshot text;
  private Syntax syntax;
  private long generation;
  private boolean requested;
  private boolean closed;
  private int nextKey;
  private volatile Folds folds = Folds.NONE;
  private Runnable regionsListener;
  private int loggedOverLong;

  /** Confined to the task draining the queue. */
  private SyntaxEngine engine;

  private long engineGeneration;
  private long engineRevision;

  /** One instruction for the worker. */
  private sealed interface Message permits Start, Change, View, Forget, Request {}

  private record Start(long generation, Syntax syntax, TextSnapshot text, long revision)
      implements Message {}

  private record Change(List<TextEdit> edits, TextSnapshot text, long revision)
      implements Message {}

  private record View(int key, int from, int to) implements Message {}

  private record Forget(int key) implements Message {}

  private record Request(int from, int to) implements Message {}

  /** The fold regions of one revision; published as one value, so any thread may read them. */
  private record Folds(long revision, List<int[]> regions) {
    static final Folds NONE = new Folds(-1, List.of());
  }

  /** One engine output, tokens already mapped to roles. */
  private record Output(
      long generation,
      long revision,
      int[] lines,
      int[][] tokens,
      int fromOffset,
      int toOffset,
      List<int[]> regions,
      int overLong) {}

  /**
   * Creates the highlighter, plain until {@link #setSyntax}, and starts following the document.
   *
   * @param document the document
   * @param workers runs the engine
   * @param ui receives its output
   * @param repaint repaints every view of the document
   * @param maxLineChars lines longer than this stay plain
   */
  SyntaxHighlighter(
      Document document, Workers workers, UiDispatcher ui, Runnable repaint, int maxLineChars) {
    this.document = document;
    this.workers = workers;
    this.ui = ui;
    this.repaint = repaint;
    this.maxLineChars = maxLineChars;
    this.text = document.text();
    document.addListener(this);
  }

  /**
   * Switches the grammar; the cached tokens and the fold regions are dropped.
   *
   * @param next the compiled grammar, or null for plain text
   */
  void setSyntax(Syntax next) {
    if (closed) {
      return;
    }
    generation++;
    syntax = next;
    cache.clear();
    folds = Folds.NONE;
    requested = false;
    text = document.text();
    if (next != null) {
      post(new Start(generation, next, text, document.revision()));
      for (Map.Entry<Object, int[]> view : views.entrySet()) {
        int[] lines = view.getValue();
        post(new View(keys.get(view.getKey()), lines[0], lines[1]));
        post(new Request(lines[0], lines[1]));
      }
    } else {
      post(new Start(generation, null, text, document.revision()));
    }
    repaint.run();
    if (regionsListener != null) {
      regionsListener.run();
    }
  }

  /**
   * Returns the grammar.
   *
   * @return the compiled grammar, or null for plain text
   */
  Syntax syntax() {
    return syntax;
  }

  /**
   * Sets what runs when the fold regions change.
   *
   * @param listener the listener, or null
   */
  void setRegionsListener(Runnable listener) {
    regionsListener = listener;
  }

  /**
   * Records the model lines a view shows and asks for those it lacks.
   *
   * @param view identifies the view
   * @param fromOffset the model offset of its first visible char
   * @param toOffset the model offset after its last visible char
   */
  void viewportChanged(Object view, int fromOffset, int toOffset) {
    if (closed) {
      return;
    }
    TextSnapshot current = document.text();
    int from = current.lineAt(Math.min(fromOffset, current.length()));
    int to = current.lineAt(Math.min(Math.max(fromOffset, toOffset), current.length()));
    int[] old = views.get(view);
    if (old != null && old[0] == from && old[1] == to) {
      return;
    }
    Integer key = keys.get(view);
    if (key == null) {
      key = nextKey++;
      keys.put(view, key);
    }
    views.put(view, new int[] {from, to});
    if (syntax != null) {
      post(new View(key, from, to));
      if (missing(from, to)) {
        post(new Request(from, to));
      }
    }
  }

  /**
   * Stops following a view.
   *
   * @param view the view
   */
  void removeView(Object view) {
    views.remove(view);
    Integer key = keys.remove(view);
    if (key != null && syntax != null) {
      post(new Forget(key));
    }
  }

  /** Stops following the document and drops the queued work. */
  void dispose() {
    if (!closed) {
      closed = true;
      cancel.cancel();
      inbox.clear();
      document.removeListener(this);
    }
  }

  @Override
  public int[] tokens(int line) {
    int[] tokens = cache.get(line);
    if (tokens == null && syntax != null && !requested && !closed) {
      for (int[] lines : views.values()) {
        if (line >= lines[0] && line <= lines[1]) {
          requested = true;
          post(new Request(lines[0], lines[1]));
          break;
        }
      }
    }
    return tokens;
  }

  @Override
  public List<int[]> regions(TextSnapshot snapshot, long revision) {
    Folds current = folds;
    return revision == current.revision() ? current.regions() : List.of();
  }

  @Override
  public void textChanged(DocumentChange change) {
    TextSnapshot after = document.text();
    cache.apply(change.edits(), text, after.lineCount());
    text = after;
    if (syntax != null) {
      post(new Change(change.edits(), after, change.revision()));
    }
  }

  @Override
  public void dirtyChanged(boolean dirty) {
    // Highlighting follows the text only.
  }

  private boolean missing(int from, int to) {
    for (int line = from; line <= to; line++) {
      if (cache.get(line) == null) {
        return true;
      }
    }
    return false;
  }

  private void post(Message message) {
    inbox.add(message);
    if (scheduled.compareAndSet(false, true)) {
      submit();
    }
  }

  /** Submits the task that drains the queue and runs the engine. */
  private void submit() {
    try {
      workers.executeCpu(
          new Runnable() {
            @Override
            public void run() {
              drain();
            }
          });
    } catch (RejectedExecutionException e) {
      scheduled.set(false);
    }
  }

  /** Applies the queued messages, runs one engine slice, posts its output, and resubmits. */
  @WorkerThread
  private void drain() {
    try {
      for (Message m = inbox.poll(); m != null; m = inbox.poll()) {
        accept(m);
      }
      if (engine != null && !engine.idle() && !cancel.isCanceled()) {
        SyntaxEngine.Slice slice = engine.run(System.nanoTime() + SLICE_NANOS, cancel);
        if (!slice.isEmpty()) {
          publish(slice);
        }
      }
    } catch (CancellationException e) {
      inbox.clear();
      engine = null;
    } finally {
      if (cancel.isCanceled()) {
        scheduled.set(false);
      } else if ((engine != null && !engine.idle()) || !inbox.isEmpty()) {
        submit();
      } else {
        scheduled.set(false);
        if (!inbox.isEmpty() && scheduled.compareAndSet(false, true)) {
          submit();
        }
      }
    }
  }

  @WorkerThread
  private void accept(Message m) {
    if (m instanceof Start s) {
      engine = s.syntax() == null ? null : new SyntaxEngine(s.syntax(), s.text(), maxLineChars);
      engineGeneration = s.generation();
      engineRevision = s.revision();
    } else if (engine == null) {
      return;
    } else if (m instanceof Change c) {
      engine.edit(c.edits(), c.text());
      engineRevision = c.revision();
    } else if (m instanceof View v) {
      engine.setViewport(v.key(), v.from(), v.to());
    } else if (m instanceof Forget f) {
      engine.removeViewport(f.key());
    } else if (m instanceof Request r) {
      engine.request(r.from(), r.to());
    }
  }

  @WorkerThread
  private void publish(SyntaxEngine.Slice slice) {
    int[][] roles = new int[slice.tokens().length][];
    for (int i = 0; i < roles.length; i++) {
      roles[i] = TokenCache.toRoles(slice.tokens()[i]);
    }
    TextSnapshot read = engine.text();
    int fromOffset = slice.from() < 0 ? -1 : read.offsetOfLine(slice.from());
    int toOffset =
        slice.from() < 0
            ? -1
            : slice.to() < read.lineCount() ? read.offsetOfLine(slice.to()) : read.length();
    final Output output =
        new Output(
            engineGeneration,
            engineRevision,
            slice.lines(),
            roles,
            fromOffset,
            toOffset,
            slice.regions(),
            slice.overLong());
    ui.asyncExec(
        new Runnable() {
          @Override
          public void run() {
            apply(output);
          }
        });
  }

  /** Applies an engine output on the UI thread. */
  private void apply(Output output) {
    if (closed || output.generation() != generation) {
      return;
    }
    requested = false;
    long revision = document.revision();
    TextSnapshot current = document.text();
    boolean shown = false;
    if (output.fromOffset() >= 0) {
      int from = output.fromOffset();
      int to = output.toOffset();
      if (output.revision() != revision) {
        PositionMap positions = document.positions();
        from = positions.map(output.revision(), from, Bias.BEFORE);
        to = positions.map(output.revision(), to, Bias.AFTER);
      }
      if (from == PositionMap.UNMAPPABLE || to == PositionMap.UNMAPPABLE) {
        cache.clear();
        shown = true;
      } else {
        int first = current.lineAt(from);
        int last = current.lineAt(Math.max(from, to));
        cache.remove(first, last);
        shown = visible(first, last);
      }
    }
    if (output.revision() == revision) {
      for (int i = 0; i < output.lines().length; i++) {
        cache.put(output.lines()[i], output.tokens()[i]);
        shown |= visible(output.lines()[i], output.lines()[i]);
      }
      if (output.regions() != null) {
        folds = new Folds(revision, List.copyOf(output.regions()));
        if (output.overLong() != loggedOverLong) {
          loggedOverLong = output.overLong();
          if (loggedOverLong > 0) {
            LOG.info(
                "{} lines over {} chars shown without syntax colors", loggedOverLong, maxLineChars);
          }
        }
        if (regionsListener != null) {
          regionsListener.run();
        }
      }
    }
    if (shown) {
      repaint.run();
    }
    if (output.revision() == revision) {
      for (int[] lines : views.values()) {
        if (missing(lines[0], lines[1])) {
          requested = true;
          post(new Request(lines[0], lines[1]));
        }
      }
    }
  }

  /** Reports whether a view shows any line of a range. */
  private boolean visible(int first, int last) {
    for (int[] lines : views.values()) {
      if (first <= lines[1] && last >= lines[0]) {
        return true;
      }
    }
    return false;
  }

  /**
   * Reports whether the worker has queued or running work, for tests.
   *
   * @return true while a task is scheduled
   */
  boolean busy() {
    return scheduled.get();
  }
}
