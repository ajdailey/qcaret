package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.view.ActivityIndicator;
import io.github.ajdailey.qcaret.app.view.ClipboardService;
import io.github.ajdailey.qcaret.app.view.HexSelection;
import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import io.github.ajdailey.qcaret.core.buffer.TextSnapshot;
import io.github.ajdailey.qcaret.core.concurrent.WorkerThread;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.hex.ByteOffsets;
import io.github.ajdailey.qcaret.core.hex.EncodedHexSource;
import io.github.ajdailey.qcaret.core.hex.FileHexSource;
import io.github.ajdailey.qcaret.core.hex.HexSource;
import io.github.ajdailey.qcaret.core.text.LogText;
import java.io.IOException;

/**
 * Copy as Hex and Copy as Text of a hex view's selection. A selection over {@value #MAX_BYTES}
 * bytes is refused with a message. Hex is space-separated pairs. Text from a tab's pane is the
 * document's chars whose bytes the selection touches, so a partly selected char is copied whole;
 * from a file it is the bytes decoded as ISO-8859-1, one char per byte. Control chars are kept. The
 * bytes are read on a worker under the busy cursor, and the result is dropped when the source
 * changed meanwhile. Confined to the UI thread.
 */
final class HexCopier {
  /** The largest selection copied, in bytes. */
  static final int MAX_BYTES = 16 << 20;

  /** The view a copy runs for. */
  interface Target {
    /** Returns the bytes, or null while there are none. */
    HexSource source();

    /** Returns a number that changes whenever the source does. */
    long generation();

    /** Returns the busy cursor. */
    ActivityIndicator busy();

    /** Returns the clipboard. */
    ClipboardService clipboard();

    /** Shows a message; empty clears it. */
    void message(String text);
  }

  private final Workers workers;
  private final UiDispatcher ui;

  HexCopier(Workers workers, UiDispatcher ui) {
    this.workers = workers;
    this.ui = ui;
  }

  /**
   * Copies a selection.
   *
   * @param target the view
   * @param selection the selection; an empty one copies nothing
   * @param hex true for Copy as Hex, false for Copy as Text
   */
  void copy(final Target target, HexSelection selection, final boolean hex) {
    final HexSource source = target.source();
    final long start = selection.start();
    final long length = selection.length();
    if (source == null || length == 0) {
      return;
    }
    if (length > MAX_BYTES) {
      target.message(UiText.HEX_COPY_TOO_LARGE.format(MAX_BYTES >> 20));
      return;
    }
    final long generation = target.generation();
    BusyWork work =
        new BusyWork(ui, target.busy()) {
          private String text;
          private IOException error;

          @Override
          @WorkerThread
          void work() {
            try {
              text = format(source, start, (int) length, hex);
            } catch (IOException e) {
              error = e;
            }
          }

          @Override
          void finished(boolean completed) {
            if (generation != target.generation()) {
              return;
            }
            if (error != null) {
              target.message(UiText.HEX_READ_FAILED.format(LogText.oneLine(error.getMessage())));
            } else if (text != null) {
              target.clipboard().setText(text);
            }
          }
        };
    if (source instanceof FileHexSource) {
      work.start(workers, Workers.Kind.IO);
    } else {
      work.startCpu(workers);
    }
  }

  /** Returns the clipboard text of a range. */
  @WorkerThread
  static String format(HexSource source, long start, int length, boolean hex) throws IOException {
    if (!hex && source instanceof EncodedHexSource encoded) {
      return chars(encoded.offsets(), start, start + length);
    }
    byte[] bytes = new byte[length];
    source.read(start, bytes, length, null);
    return hex ? HexOffsetText.hexPairs(bytes, length) : new String(bytes, source.charset());
  }

  /** Returns the chars whose bytes overlap {@code [start, end)}; the BOM maps to no char. */
  static String chars(ByteOffsets offsets, long start, long end) {
    TextSnapshot text = offsets.text();
    int from = offsets.charOffset(start);
    int to = charEnd(offsets, end);
    return from < to ? text.subSequence(from, to).toString() : "";
  }

  /** Returns the offset after the char holding byte {@code end - 1}; 0 inside the BOM. */
  static int charEnd(ByteOffsets offsets, long end) {
    TextSnapshot text = offsets.text();
    if (end >= offsets.size()) {
      return text.length();
    }
    int c = offsets.charOffset(end);
    if (offsets.byteOffset(c) < end) {
      c += Character.isHighSurrogate(text.charAt(c)) && c + 1 < text.length() ? 2 : 1;
    }
    return c;
  }
}
