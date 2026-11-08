package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.editor.LineChanges;
import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import io.github.ajdailey.qcaret.core.buffer.TextSnapshot;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.concurrent.WorkerThread;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.document.Document;
import io.github.ajdailey.qcaret.core.document.DocumentFiles;
import io.github.ajdailey.qcaret.core.document.LoadCost;
import io.github.ajdailey.qcaret.core.document.LoadOptions;
import io.github.ajdailey.qcaret.core.document.LoadReservation;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CancellationException;

/**
 * Gives a document restored from a recovery file the file on disk as its {@link
 * Document#savedText}, the baseline of the modified-line marks. The file is read on a {@code
 * qcaret-io-} worker as a comparison reads it ({@link CompareController#load}), in the document's
 * charset, with its cost reserved from the budget during the read. The text applies while the
 * document is open and its saved text is still the recovered one; a save or reload in between wins.
 * A missing or unreadable file, one over {@value #MAX_BYTES} bytes, or one the budget cannot hold
 * leaves the recovered text as the baseline, so no line is marked.
 */
final class RecoveredBaseline {
  /** Largest file read: {@link LineChanges#MAX_CHARS} chars at four bytes each. */
  static final long MAX_BYTES = 4L * LineChanges.MAX_CHARS;

  private RecoveredBaseline() {}

  /**
   * Starts the read for a restored document.
   *
   * @param d the restored document
   * @param file its file's real path
   * @param workers the window's workers
   * @param ui the dispatcher the result returns through
   * @param budget the documents' heap share
   * @param options the window's load options
   */
  static void read(
      final OpenDocument d,
      final Path file,
      Workers workers,
      final UiDispatcher ui,
      final MemoryBudget budget,
      final LoadOptions options) {
    final Document document = d.document;
    final TextSnapshot recovered = document.savedText();
    final Charset charset = document.encoding().charset();
    final boolean binary = d.binary;
    workers.tryStart(
        Workers.Kind.IO,
        new Runnable() {
          @Override
          public void run() {
            final TextSnapshot text = readFile(file, options, charset, binary, budget);
            if (text == null) {
              return;
            }
            ui.asyncExec(
                new Runnable() {
                  @Override
                  public void run() {
                    if (!d.closed && document.savedText() == recovered) {
                      document.setSavedText(text);
                    }
                  }
                });
          }
        });
  }

  @WorkerThread
  private static TextSnapshot readFile(
      Path file, LoadOptions options, Charset charset, boolean binary, MemoryBudget budget) {
    try {
      if (Files.size(file) > MAX_BYTES) {
        return null;
      }
      LoadCost cost = DocumentFiles.estimate(file, options.limits());
      try (LoadReservation reservation = LoadReservation.reserve(budget, cost)) {
        if (reservation == null) {
          return null;
        }
        return CompareController.load(
                file, options, charset, binary, reservation, new CancellationToken())
            .document()
            .text();
      }
    } catch (IOException | CancellationException e) {
      return null;
    }
  }
}
