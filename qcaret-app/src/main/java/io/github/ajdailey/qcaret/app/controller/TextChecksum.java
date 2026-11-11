package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.view.HashTextDisplay;
import io.github.ajdailey.qcaret.app.view.HashTextInput;
import io.github.ajdailey.qcaret.app.view.MainView;
import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import io.github.ajdailey.qcaret.core.concurrent.WorkerThread;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.io.ChecksumAlgorithm;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.concurrent.RejectedExecutionException;

/**
 * Tools > Generate: one dialog's controller. Each change of the field's text is hashed in UTF-8 on
 * a {@code qcaret-cpu-} worker; a result for an older text is dropped. Copy copies the hash of the
 * current text, and does nothing while it is computed. Confined to the UI thread.
 */
final class TextChecksum implements HashTextInput {
  private final ChecksumAlgorithm algorithm;
  private final Workers workers;
  private final UiDispatcher ui;
  private final MainView view;
  private HashTextDisplay display;
  private long generation;
  private String current;

  TextChecksum(ChecksumAlgorithm algorithm, Workers workers, UiDispatcher ui, MainView view) {
    this.algorithm = algorithm;
    this.workers = workers;
    this.ui = ui;
    this.view = view;
  }

  /** Opens the dialog with the hash of the empty text. */
  void open() {
    display =
        view.showHashText(
            UiText.CHECKSUM_TEXT_TITLE.format(algorithm.javaName()),
            ChecksumTools.note(algorithm, UiText.CHECKSUM_TEXT_NOTE.text()),
            this);
    textChanged("");
  }

  @Override
  public void textChanged(final String text) {
    if (display == null) {
      return;
    }
    final long run = ++generation;
    current = null;
    try {
      workers.executeCpu(
          new Runnable() {
            @Override
            public void run() {
              final String hex = hash(algorithm, text);
              ui.asyncExec(
                  new Runnable() {
                    @Override
                    public void run() {
                      hashed(run, hex);
                    }
                  });
            }
          });
    } catch (RejectedExecutionException e) {
      // Shutting down.
    }
  }

  private void hashed(long run, String hex) {
    if (run != generation || display == null) {
      return;
    }
    current = hex;
    display.showHash(hex);
  }

  /**
   * Hashes a text's UTF-8 bytes; a lone surrogate encodes as {@code ?}.
   *
   * @param algorithm an available algorithm
   * @param text the text
   * @return the digest in lower-case hex
   */
  @WorkerThread
  private static String hash(ChecksumAlgorithm algorithm, String text) {
    return HexFormat.of()
        .formatHex(ChecksumTools.digest(algorithm).digest(text.getBytes(StandardCharsets.UTF_8)));
  }

  @Override
  public void copy() {
    if (current != null) {
      view.setText(current);
    }
  }

  @Override
  public void closed() {
    display = null;
  }
}
