package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.view.ActivityIndicator;
import io.github.ajdailey.qcaret.app.view.HexSelection;
import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.WorkerThread;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.hex.ByteSearch;
import io.github.ajdailey.qcaret.core.hex.FileHexSource;
import io.github.ajdailey.qcaret.core.hex.HexPattern;
import io.github.ajdailey.qcaret.core.hex.HexPatternException;
import io.github.ajdailey.qcaret.core.hex.HexSource;
import io.github.ajdailey.qcaret.core.text.LogText;
import java.io.IOException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongConsumer;

/**
 * Find in one hex view: a hex pattern with {@code ??} wildcards, or text encoded in the source's
 * charset, searched by {@link ByteSearch} from the selection, wrapping, under the busy cursor. The
 * view's message line shows the progress in percent, then the result. Esc cancels the search; a new
 * one cancels the running one; a result for a replaced source is dropped. Confined to the UI
 * thread.
 */
final class HexFind {
  /** The view a find runs for. */
  interface Target {
    /** Returns the bytes, or null while there are none. */
    HexSource source();

    /** Returns a number that changes whenever the source does. */
    long generation();

    /** Returns the busy cursor. */
    ActivityIndicator busy();

    /** Selects a match and scrolls it into view. */
    void found(long start, long length);

    /** Shows a message; empty clears it. */
    void message(String text);
  }

  private final Workers workers;
  private final UiDispatcher ui;
  private CancellationToken running;
  private String text = "";
  private boolean hex = true;

  HexFind(Workers workers, UiDispatcher ui) {
    this.workers = workers;
    this.ui = ui;
  }

  /** Returns the last pattern's text. */
  String text() {
    return text;
  }

  /** Reports whether the last pattern was hex. */
  boolean hex() {
    return hex;
  }

  /**
   * Finds the next match after the selection's first byte, or the previous one before it.
   *
   * @param target the view
   * @param value the pattern's text
   * @param hexPattern true for a hex pattern
   * @param forward the direction
   * @param selection the view's selection
   */
  void find(
      final Target target,
      String value,
      boolean hexPattern,
      final boolean forward,
      HexSelection selection) {
    text = value;
    hex = hexPattern;
    final HexSource source = target.source();
    if (source == null) {
      return;
    }
    final HexPattern pattern;
    try {
      pattern = hexPattern ? HexPattern.hex(value) : HexPattern.text(value, source.charset());
    } catch (HexPatternException e) {
      target.message(refusal(e, source));
      return;
    }
    cancel();
    long first = selection.length() > 0 ? selection.start() : selection.caret();
    final long from = forward ? first + (selection.length() > 0 ? 1 : 0) : first - 1;
    final CancellationToken token = new CancellationToken();
    running = token;
    final long generation = target.generation();
    final long total = Math.max(1, source.size() - pattern.length() + 1);
    final AtomicInteger shown = new AtomicInteger();
    final LongConsumer progress =
        new LongConsumer() {
          @Override
          public void accept(long examined) {
            final int percent = (int) Math.min(100, examined * 100 / total);
            if (shown.getAndSet(percent) != percent) {
              ui.asyncExec(
                  new Runnable() {
                    @Override
                    public void run() {
                      if (running == token) {
                        target.message(UiText.HEX_FIND_PROGRESS.format(percent));
                      }
                    }
                  });
            }
          }
        };
    BusyWork work =
        new BusyWork(ui, target.busy()) {
          private long hit = -1;
          private IOException error;

          @Override
          @WorkerThread
          void work() {
            try {
              hit = ByteSearch.find(source, pattern, from, forward, true, token, progress);
            } catch (IOException e) {
              error = e;
            } catch (CancellationException e) {
              hit = -1;
            }
          }

          @Override
          void finished(boolean completed) {
            if (running != token) {
              return;
            }
            running = null;
            if (generation != target.generation()) {
              return;
            }
            if (error != null) {
              target.message(UiText.HEX_FIND_FAILED.format(LogText.oneLine(error.getMessage())));
            } else if (hit < 0) {
              target.message(UiText.HEX_FIND_NOT_FOUND.text());
            } else {
              boolean wrapped = forward ? hit < from : hit > from;
              target.found(hit, pattern.length());
              target.message(wrapped ? UiText.HEX_FIND_WRAPPED.text() : "");
            }
          }
        };
    boolean started =
        source instanceof FileHexSource
            ? work.start(workers, Workers.Kind.IO)
            : work.startCpu(workers);
    if (!started) {
      running = null;
    }
  }

  /**
   * Stops the running find.
   *
   * @return true when one was running
   */
  boolean cancel() {
    if (running == null) {
      return false;
    }
    running.cancel();
    running = null;
    return true;
  }

  private static String refusal(HexPatternException e, HexSource source) {
    return switch (e.reason()) {
      case EMPTY -> UiText.HEX_PATTERN_EMPTY.text();
      case ODD_DIGITS -> UiText.HEX_PATTERN_ODD_DIGITS.format(e.index() + 1);
      case NOT_HEX -> UiText.HEX_PATTERN_NOT_HEX.format(e.index() + 1);
      case WILDCARDS_ONLY -> UiText.HEX_PATTERN_WILDCARDS_ONLY.text();
      case TOO_LONG -> UiText.HEX_PATTERN_TOO_LONG.format(HexPattern.MAX_LENGTH);
      case UNMAPPABLE ->
          UiText.HEX_PATTERN_UNMAPPABLE.format(source.charset().displayName(), e.index() + 1);
    };
  }
}
