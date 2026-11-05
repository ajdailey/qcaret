package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.view.Comparison;
import io.github.ajdailey.qcaret.app.view.MainView;
import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import io.github.ajdailey.qcaret.core.buffer.TextContent;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.concurrent.WorkerThread;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.document.Document;
import io.github.ajdailey.qcaret.core.document.DocumentFiles;
import io.github.ajdailey.qcaret.core.document.LoadCost;
import io.github.ajdailey.qcaret.core.document.LoadOptions;
import io.github.ajdailey.qcaret.core.document.LoadedFile;
import io.github.ajdailey.qcaret.core.document.TextCodingException;
import io.github.ajdailey.qcaret.core.text.LogText;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

/**
 * Compares two open documents, or a document with its file on disk, in a read-only window. Confined
 * to the UI thread. The texts are snapshots taken when the comparison starts; the file is read on a
 * {@code qcaret-io-} worker, decoded as {@link #load} describes, and the lines are compared on a
 * {@code qcaret-cpu-} worker ({@link Comparisons}). A side over the bounds gets a notice instead of
 * a window. Each comparison counts as one activity operation until it ends; {@link #dispose}
 * cancels the running ones.
 */
public final class CompareController {
  private final DocumentController documents;
  private final Workers workers;
  private final UiDispatcher ui;
  private final MainView view;
  private final MemoryBudget budget;
  private final Set<CancellationToken> running = ConcurrentHashMap.newKeySet();
  private boolean disposed;

  /**
   * Creates the controller.
   *
   * @param documents the window's documents
   * @param workers runs the reads and the comparisons
   * @param ui receives the results
   * @param view shows the chooser, the notices and the window
   * @param budget charged while a file on disk is read
   */
  public CompareController(
      DocumentController documents,
      Workers workers,
      UiDispatcher ui,
      MainView view,
      MemoryBudget budget) {
    this.documents = documents;
    this.workers = workers;
    this.ui = ui;
    this.view = view;
    this.budget = budget;
  }

  /** Asks for another open document and compares the selected one with it. */
  public void compareDocuments() {
    final Document left = documents.activeDocument();
    if (left == null) {
      return;
    }
    final List<Document> others = new ArrayList<Document>();
    List<String> labels = new ArrayList<String>();
    List<String> all = documents.labels();
    List<Document> open = documents.openDocuments();
    for (int i = 0; i < open.size(); i++) {
      if (open.get(i) != left) {
        others.add(open.get(i));
        labels.add(all.get(i));
      }
    }
    if (others.isEmpty()) {
      view.showNotice(UiText.COMPARE_NO_OTHER.text());
      return;
    }
    view.chooseDocument(
        labels,
        new Consumer<Integer>() {
          @Override
          public void accept(Integer index) {
            Document right = others.get(index);
            if (!disposed && documents.isOpen(left) && documents.isOpen(right)) {
              compare(
                  documents.nameOf(left),
                  left.text(),
                  documents.nameOf(right),
                  right.text(),
                  new CancellationToken());
            }
          }
        });
  }

  /** Compares the selected document's file on disk with its text. */
  public void compareWithDisk() {
    Document document = documents.activeDocument();
    Path file = documents.activeOpenedPath();
    if (document != null && file != null) {
      compareWithDisk(document, file, documents.activeBinary());
    }
  }

  /**
   * Compares a document's file on disk with its text: the file on the left.
   *
   * @param document the open document
   * @param file the path the user opened
   * @param binary true for a binary file's view, read as ISO-8859-1
   */
  void compareWithDisk(final Document document, final Path file, final boolean binary) {
    if (disposed) {
      return;
    }
    final String name = documents.nameOf(document);
    final TextContent text = document.text();
    final LoadOptions base = documents.loadOptions();
    final CancellationToken token = new CancellationToken();
    running.add(token);
    view.setActive();
    boolean started =
        workers.tryStart(
            Workers.Kind.IO,
            new Runnable() {
              @Override
              public void run() {
                boolean handedOn = false;
                try {
                  Document disk =
                      readDisk(file, base, document.encoding().charset(), binary, token);
                  if (disk == null) {
                    post(UiText.COMPARE_TOO_LARGE.format(name));
                  } else {
                    handedOn = true;
                    build(UiText.COMPARE_DISK.format(name), disk.text(), name, text, token);
                  }
                } catch (NoSuchFileException e) {
                  post(UiText.COMPARE_NO_FILE.format(name));
                } catch (IOException e) {
                  post(UiText.COMPARE_FAILED.format(name, LogText.oneLine(e.getMessage())));
                } catch (CancellationException e) {
                  // Disposed.
                } finally {
                  if (!handedOn) {
                    end(token);
                  }
                }
              }
            });
    if (!started) {
      end(token);
    }
  }

  /**
   * Reads a file as a reload does ({@link #load}) while its cost is reserved from the budget.
   *
   * @return the document, or null when the budget cannot hold it
   */
  @WorkerThread
  private Document readDisk(
      Path file, LoadOptions base, Charset charset, boolean binary, CancellationToken token)
      throws IOException {
    LoadCost cost = DocumentFiles.estimate(file, base.limits());
    if (!budget.reserve(cost.reserveBytes())) {
      return null;
    }
    try {
      return load(file, base, charset, binary, token).document();
    } finally {
      budget.release(cost.reserveBytes());
    }
  }

  /**
   * Loads a file for a reload or a comparison: in the document's charset, with detection when that
   * charset does not decode the bytes, or as ISO-8859-1 for a binary file's view.
   *
   * @param file the path the user opened
   * @param base the window's load options
   * @param charset the document's charset
   * @param binary true for a binary file's view
   * @param token canceled by a close
   * @return the loaded file
   * @throws IOException when the file cannot be read or decoded
   */
  @WorkerThread
  static LoadedFile load(
      Path file, LoadOptions base, Charset charset, boolean binary, CancellationToken token)
      throws IOException {
    if (binary) {
      return DocumentFiles.load(file, base.asBinary(), token);
    }
    try {
      return DocumentFiles.load(file, base.withForced(charset), token);
    } catch (TextCodingException e) {
      return DocumentFiles.load(file, base.withForced(null), token);
    }
  }

  private void compare(
      String leftTitle,
      TextContent left,
      String rightTitle,
      TextContent right,
      CancellationToken token) {
    running.add(token);
    view.setActive();
    build(leftTitle, left, rightTitle, right, token);
  }

  /** Compares on the CPU pool, then shows the window; ends the operation. */
  private void build(
      final String leftTitle,
      final TextContent left,
      final String rightTitle,
      final TextContent right,
      final CancellationToken token) {
    try {
      workers.executeCpu(
          new Runnable() {
            @Override
            public void run() {
              Comparison built = null;
              String notice = null;
              try {
                if (!Comparisons.fits(left) || !Comparisons.fits(right)) {
                  notice =
                      UiText.COMPARE_TOO_LARGE.format(
                          Comparisons.fits(left) ? rightTitle : leftTitle);
                } else {
                  built = Comparisons.build(leftTitle, left, rightTitle, right, token);
                }
              } catch (CancellationException e) {
                // Disposed.
              } finally {
                final Comparison comparison = built;
                final String message = notice;
                ui.asyncExec(
                    new Runnable() {
                      @Override
                      public void run() {
                        end(token);
                        if (disposed || token.isCanceled()) {
                          return;
                        }
                        if (comparison != null) {
                          view.showComparison(comparison);
                        } else if (message != null) {
                          view.showNotice(message);
                        }
                      }
                    });
              }
            }
          });
    } catch (RejectedExecutionException e) {
      end(token);
    }
  }

  private void post(final String notice) {
    ui.asyncExec(
        new Runnable() {
          @Override
          public void run() {
            if (!disposed) {
              view.showNotice(notice);
            }
          }
        });
  }

  private void end(CancellationToken token) {
    if (running.remove(token)) {
      view.setIdle();
    }
  }

  /**
   * Counts the comparisons not yet ended; for tests.
   *
   * @return the count
   */
  int running() {
    return running.size();
  }

  /** Cancels the running comparisons; none shows afterwards. */
  public void dispose() {
    disposed = true;
    for (CancellationToken token : running) {
      token.cancel();
    }
  }
}
