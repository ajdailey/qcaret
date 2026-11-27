package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.view.ActivityIndicator;
import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import io.github.ajdailey.qcaret.core.buffer.TextSnapshot;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.WorkerThread;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.document.Document;
import io.github.ajdailey.qcaret.core.document.DocumentChange;
import io.github.ajdailey.qcaret.core.document.DocumentListener;
import io.github.ajdailey.qcaret.core.document.TextEdit;
import io.github.ajdailey.qcaret.core.hex.ByteOffsets;
import io.github.ajdailey.qcaret.core.hex.EncodedHexSource;
import io.github.ajdailey.qcaret.core.text.TextEncoding;
import java.util.concurrent.CancellationException;
import java.util.concurrent.RejectedExecutionException;

/**
 * The {@link ByteOffsets} of a tab's document for its hex pane: the text as Save writes it. While
 * the pane shows, an edit marks the bytes stale at once and rebuilds the offsets {@value
 * #DELAY_MILLIS} ms after the last edit on the CPU pool, without the busy cursor, from the block
 * before the first changed char. {@link #start} builds under the busy cursor. A build carries the
 * document revision it read and is dropped when an edit overtook it; there is no partial index, so
 * the pane shows the previous bytes dimmed until a build lands. A change of the document's encoding
 * builds the whole text again. Confined to the UI thread.
 */
final class HexText {
  /** The typing pause before a rebuild, as the Markdown live render waits. */
  static final int DELAY_MILLIS = MarkdownPreview.DELAY_MILLIS;

  /** Receives the builds. */
  interface Target {
    /** Offsets of the document's current revision landed. */
    void built(EncodedHexSource source);

    /** An edit made the shown bytes stale. */
    void stale();

    /** The document's charset has no encoder. */
    void unsupported();

    /** Returns the busy cursor for a build the user started. */
    ActivityIndicator busy();
  }

  private final Document document;
  private final Workers workers;
  private final UiDispatcher ui;
  private final Target target;
  private final Runnable timer =
      new Runnable() {
        @Override
        public void run() {
          rebuild();
        }
      };
  private final DocumentListener follower =
      new DocumentListener() {
        @Override
        public void textChanged(DocumentChange change) {
          edited(change);
        }

        @Override
        public void dirtyChanged(boolean dirty) {
          // The bytes do not depend on the dirty state.
        }
      };
  private ByteOffsets offsets;
  private long builtRevision = -1;
  private int firstChanged = Integer.MAX_VALUE;
  private boolean following;
  private long stoppedAt = -1;
  private CancellationToken running;
  private long generation;

  HexText(Document document, Workers workers, UiDispatcher ui, Target target) {
    this.document = document;
    this.workers = workers;
    this.ui = ui;
    this.target = target;
  }

  /**
   * Starts following edits; builds under the busy cursor unless the offsets are current.
   *
   * @return true when the offsets are current already
   */
  boolean start() {
    if (!following) {
      following = true;
      document.addListener(follower);
      if (stoppedAt >= 0 && stoppedAt != document.revision()) {
        // Edits made while stopped were not followed.
        firstChanged = 0;
      }
      stoppedAt = -1;
    }
    if (current() != null) {
      return true;
    }
    if (running == null) {
      buildNow();
    }
    return false;
  }

  /** Stops following edits and cancels a running build. */
  void stop() {
    if (following) {
      following = false;
      stoppedAt = document.revision();
      document.removeListener(follower);
    }
    ui.cancelTimer(timer);
    cancelRunning();
  }

  /** Builds the whole text again after the document's encoding changed. */
  void encodingChanged() {
    if (following && current() == null) {
      cancelRunning();
      ui.cancelTimer(timer);
      target.stale();
      buildNow();
    }
  }

  /**
   * Returns the offsets of the document's current revision and encoding.
   *
   * @return the offsets, or null while stale
   */
  ByteOffsets current() {
    return offsets != null
            && builtRevision == document.revision()
            && offsets.encoding().equals(document.encoding())
        ? offsets
        : null;
  }

  private void edited(DocumentChange change) {
    for (TextEdit edit : change.edits()) {
      firstChanged = Math.min(firstChanged, edit.offset());
    }
    cancelRunning();
    target.stale();
    ui.cancelTimer(timer);
    ui.timerExec(DELAY_MILLIS, timer);
  }

  private void cancelRunning() {
    generation++;
    if (running != null) {
      running.cancel();
      running = null;
    }
  }

  /** Builds under the busy cursor: a switch to Split or Hex, or an encoding change. */
  private void buildNow() {
    final Build build = prepare();
    boolean started =
        new BusyWork(ui, target.busy()) {
          @Override
          @WorkerThread
          void work() {
            build.run();
          }

          @Override
          void finished(boolean completed) {
            apply(build);
          }
        }.startCpu(workers);
    if (!started) {
      running = null;
    }
  }

  /** Rebuilds after a typing pause, without the busy cursor. */
  private void rebuild() {
    if (!following) {
      return;
    }
    final Build build = prepare();
    try {
      workers.executeCpu(
          new Runnable() {
            @Override
            public void run() {
              try {
                build.run();
              } finally {
                ui.asyncExec(
                    new Runnable() {
                      @Override
                      public void run() {
                        apply(build);
                      }
                    });
              }
            }
          });
    } catch (RejectedExecutionException e) {
      running = null;
    }
  }

  private Build prepare() {
    cancelRunning();
    TextEncoding encoding = document.encoding();
    boolean reuse =
        offsets != null && offsets.encoding().equals(encoding) && firstChanged != Integer.MAX_VALUE;
    running = new CancellationToken();
    return new Build(
        generation,
        document.revision(),
        document.text(),
        encoding,
        reuse ? offsets : null,
        firstChanged,
        running);
  }

  private void apply(Build build) {
    if (build.generation != generation) {
      return;
    }
    running = null;
    if (build.unsupported) {
      target.unsupported();
      return;
    }
    if (build.result == null || build.revision != document.revision()) {
      return;
    }
    offsets = build.result;
    builtRevision = build.revision;
    firstChanged = Integer.MAX_VALUE;
    target.built(new EncodedHexSource(offsets));
  }

  /** One build's input and result. */
  private static final class Build {
    final long generation;
    final long revision;
    final TextSnapshot text;
    final TextEncoding encoding;
    final ByteOffsets base;
    final int firstChanged;
    final CancellationToken token;
    volatile ByteOffsets result;
    volatile boolean unsupported;

    Build(
        long generation,
        long revision,
        TextSnapshot text,
        TextEncoding encoding,
        ByteOffsets base,
        int firstChanged,
        CancellationToken token) {
      this.generation = generation;
      this.revision = revision;
      this.text = text;
      this.encoding = encoding;
      this.base = base;
      this.firstChanged = firstChanged;
      this.token = token;
    }

    @WorkerThread
    void run() {
      try {
        result =
            base == null
                ? ByteOffsets.build(text, encoding, token)
                : base.update(text, firstChanged, token);
      } catch (CancellationException e) {
        result = null;
      } catch (UnsupportedOperationException e) {
        unsupported = true;
      }
    }
  }
}
