package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.view.MainView;
import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import io.github.ajdailey.qcaret.core.buffer.ChunkVisitor;
import io.github.ajdailey.qcaret.core.buffer.TextSnapshot;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.WorkerThread;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.document.BlockRow;
import io.github.ajdailey.qcaret.core.document.BlockSelection;
import io.github.ajdailey.qcaret.core.document.Document;
import io.github.ajdailey.qcaret.core.document.Selection;
import io.github.ajdailey.qcaret.core.io.ChecksumAlgorithm;
import io.github.ajdailey.qcaret.core.io.Checksums;
import io.github.ajdailey.qcaret.core.io.Encoders;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;

/**
 * Tools > Generate from Selection into Clipboard: hashes the bytes Copy's text would have in the
 * saved file, on a {@code qcaret-cpu-} worker under the busy cursor from a text snapshot, then
 * copies the hex and names the charset and line ending in a notice.
 *
 * <ul>
 *   <li>Selections join in document order with the document's line ending, as Copy joins them;
 *       empty selections are skipped. A block selection's rows join the same way, empty rows
 *       included.
 *   <li>Line breaks inside a selection hash as the document holds them.
 *   <li>The text is encoded in the document's charset without a byte order mark. A char the charset
 *       cannot encode, or a charset with no encoder, copies nothing and says so.
 * </ul>
 *
 * A newer run cancels an older one. Confined to the UI thread.
 */
final class SelectionChecksum {
  private final DocumentController documents;
  private final Workers workers;
  private final UiDispatcher ui;
  private final MainView view;
  private CancellationToken running;

  /** What a run hashes, taken on the UI thread. */
  private record Request(
      ChecksumAlgorithm algorithm,
      TextSnapshot text,
      int[] ranges,
      BlockSelection block,
      int tabWidth,
      String ending,
      Charset charset,
      String endingName) {}

  SelectionChecksum(DocumentController documents, Workers workers, UiDispatcher ui, MainView view) {
    this.documents = documents;
    this.workers = workers;
    this.ui = ui;
    this.view = view;
  }

  /**
   * Hashes the active editor's selections and copies the result.
   *
   * @param algorithm an available algorithm
   */
  void copy(ChecksumAlgorithm algorithm) {
    EditorController editor = documents.activeEditor();
    if (editor == null || !editor.hasSelection()) {
      return;
    }
    Document document = editor.document();
    Charset charset = document.encoding().charset();
    if (!Encoders.writable(charset)) {
      view.showNotice(UiText.CHECKSUM_SELECTION_NO_ENCODER.format(charset.name()));
      return;
    }
    BlockSelection block = editor.selections().block();
    List<Selection> list = editor.selections().selections().selections();
    List<Integer> ranges = new ArrayList<Integer>();
    if (block == null) {
      for (Selection s : list) {
        if (!s.isEmpty()) {
          ranges.add(s.start());
          ranges.add(s.end());
        }
      }
    }
    int[] flat = new int[ranges.size()];
    for (int i = 0; i < flat.length; i++) {
      flat[i] = ranges.get(i);
    }
    final Request request =
        new Request(
            algorithm,
            document.text(),
            flat,
            block,
            document.tabWidth(),
            document.lineEnding().text(),
            charset,
            document.lineEnding().name());
    if (running != null) {
      running.cancel();
    }
    final CancellationToken token = new CancellationToken();
    running = token;
    boolean started =
        new BusyWork(ui, view) {
          @Override
          @WorkerThread
          void work() {
            String hex = null;
            boolean encoded = true;
            try {
              hex = hash(request, token);
            } catch (CharacterCodingException e) {
              encoded = false;
            } catch (CancellationException e) {
              hex = null;
            } finally {
              final String result = hex;
              final boolean ok = encoded;
              ui.asyncExec(
                  new Runnable() {
                    @Override
                    public void run() {
                      hashed(token, request, result, ok);
                    }
                  });
            }
          }
        }.startCpu(workers);
    if (!started) {
      running = null;
    }
  }

  private void hashed(CancellationToken token, Request request, String hex, boolean encoded) {
    if (token != running) {
      return;
    }
    running = null;
    String charset = request.charset().name();
    if (!encoded) {
      view.showNotice(UiText.CHECKSUM_SELECTION_UNENCODABLE.format(charset));
      return;
    }
    if (hex == null) {
      return;
    }
    view.setText(hex);
    view.showNotice(
        UiText.CHECKSUM_SELECTION_COPIED.format(
            request.algorithm().javaName(), charset, request.endingName(), hex));
  }

  /**
   * Hashes a request's ranges, or its block's rows, joined by its line ending.
   *
   * @param request the snapshot and ranges
   * @param token checked before each chunk
   * @return the digest in lower-case hex
   * @throws CharacterCodingException when a char cannot be encoded
   */
  @WorkerThread
  private static String hash(Request request, final CancellationToken token)
      throws CharacterCodingException {
    int[] ranges = request.ranges();
    if (request.block() != null) {
      List<BlockRow> rows = request.block().rows(request.text(), request.tabWidth());
      ranges = new int[2 * rows.size()];
      for (int i = 0; i < rows.size(); i++) {
        ranges[2 * i] = rows.get(i).start();
        ranges[2 * i + 1] = rows.get(i).end();
      }
    }
    final Checksums.TextDigester digester =
        new Checksums.TextDigester(request.charset(), ChecksumTools.digest(request.algorithm()));
    String ending = request.ending();
    try {
      for (int i = 0; i < ranges.length; i += 2) {
        token.check();
        if (i > 0) {
          digester.add(ending, 0, ending.length());
        }
        request
            .text()
            .readChunks(
                ranges[i],
                ranges[i + 1],
                new ChunkVisitor() {
                  @Override
                  public void chunk(CharSequence chunk, int from, int to) {
                    token.check();
                    try {
                      digester.add(chunk, from, to);
                    } catch (CharacterCodingException e) {
                      throw new Unencodable(e);
                    }
                  }
                });
      }
    } catch (Unencodable e) {
      if (e.getCause() instanceof CharacterCodingException failure) {
        throw failure;
      }
      throw e;
    }
    return digester.finish();
  }

  /** Carries an encoding failure out of a {@link ChunkVisitor}. */
  private static final class Unencodable extends RuntimeException {
    private static final long serialVersionUID = 1L;

    Unencodable(CharacterCodingException failure) {
      super(failure);
    }
  }
}
