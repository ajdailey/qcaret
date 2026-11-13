package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.view.MainView;
import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import io.github.ajdailey.qcaret.core.buffer.ChunkVisitor;
import io.github.ajdailey.qcaret.core.buffer.TextSnapshot;
import io.github.ajdailey.qcaret.core.concurrent.WorkerThread;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.io.ChecksumCheck;
import io.github.ajdailey.qcaret.core.io.ChecksumList;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.LongConsumer;

/**
 * Tools > Verify Checksums: checks the files of a {@code sha256sum}-style list ({@link
 * ChecksumList}) one after another and shows {@code <name>: OK}, {@code FAILED}, {@code MISSING} or
 * {@code unreadable} for each, then each line that did not parse, then the totals. The list is the
 * selected document's text when the document has a file, or a file chosen with Choose List, read as
 * UTF-8. Names resolve against the list file's folder.
 */
final class ChecksumVerify extends ChecksumRun<ChecksumVerify.Request> {
  private final DocumentController documents;

  /**
   * A run's list.
   *
   * @param folder the folder names resolve against
   * @param text the document's text, or null to read {@code file}
   * @param file the list file to read, or null
   */
  record Request(Path folder, TextSnapshot text, Path file) {}

  ChecksumVerify(DocumentController documents, Workers workers, UiDispatcher ui, MainView view) {
    super(workers, ui, view, documents.under(), documents.writes());
    this.documents = documents;
  }

  /** Opens the dialog and verifies the selected document's text when it has a file. */
  void open() {
    openDialog(UiText.CHECKSUM_VERIFY_TITLE.text(), "", UiText.BUTTON_CHOOSE_LIST.text());
    EditorController editor = documents.activeEditor();
    Path path = documents.activePath();
    if (editor == null || path == null) {
      display().showStatus(UiText.CHECKSUM_NO_LIST.text());
      return;
    }
    Path folder = path.toAbsolutePath().getParent();
    start(
        UiText.CHECKSUM_LIST.format(path),
        folder,
        new Request(folder, editor.document().text(), null));
  }

  @Override
  String saveName() {
    return "verify.txt";
  }

  @Override
  public void choose() {
    view.chooseFilesToOpen(
        folder(),
        new Consumer<List<Path>>() {
          @Override
          public void accept(List<Path> files) {
            if (!files.isEmpty() && display() != null) {
              Path list = files.get(0).toAbsolutePath();
              start(
                  UiText.CHECKSUM_LIST.format(list),
                  list.getParent(),
                  new Request(list.getParent(), null, list));
            }
          }
        });
  }

  @Override
  @WorkerThread
  void work(Request request, final Feed feed) {
    ChecksumList list;
    try {
      list = read(request, feed);
    } catch (IOException e) {
      feed.finish(UiText.CHECKSUM_LIST_UNREADABLE.format(reason(e)));
      return;
    }
    long total = 0;
    for (ChecksumList.Entry entry : list.entries()) {
      try {
        total += Files.size(ChecksumCheck.file(request.folder(), entry));
      } catch (IOException | InvalidPathException e) {
        // The check reports it.
      }
    }
    final long all = total;
    final long[] done = {0};
    int[] counts = new int[ChecksumCheck.Status.values().length];
    for (ChecksumList.Entry entry : list.entries()) {
      final String name = entry.name();
      if (!ChecksumTools.available(entry.algorithm())) {
        counts[ChecksumCheck.Status.UNREADABLE.ordinal()]++;
        feed.line(UiText.CHECKSUM_NO_ALGORITHM.format(name, entry.algorithm().javaName()));
        continue;
      }
      final long base = done[0];
      feed.progress(name, base, all);
      ChecksumCheck.Result result =
          ChecksumCheck.verify(
              request.folder(),
              entry,
              ChecksumTools.digest(entry.algorithm()),
              feed.token,
              new LongConsumer() {
                @Override
                public void accept(long read) {
                  done[0] = base + read;
                  feed.progress(name, done[0], all);
                }
              });
      counts[result.status().ordinal()]++;
      feed.line(
          switch (result.status()) {
            case OK -> UiText.CHECKSUM_OK.format(name);
            case FAILED -> UiText.CHECKSUM_FAILED.format(name);
            case MISSING -> UiText.CHECKSUM_MISSING.format(name);
            case UNREADABLE -> UiText.CHECKSUM_UNREADABLE.format(name, reason(result.error()));
          });
    }
    for (int line : list.badLines()) {
      feed.line(UiText.CHECKSUM_BAD_LINE.format(line));
    }
    if (list.badCount() > list.badLines().size()) {
      feed.line(UiText.CHECKSUM_MORE_BAD_LINES.format(list.badCount() - list.badLines().size()));
    }
    feed.finish(
        UiText.CHECKSUM_VERIFY_DONE.format(
            counts[ChecksumCheck.Status.OK.ordinal()],
            counts[ChecksumCheck.Status.FAILED.ordinal()],
            counts[ChecksumCheck.Status.MISSING.ordinal()],
            counts[ChecksumCheck.Status.UNREADABLE.ordinal()],
            list.badCount()));
  }

  /** Parses the request's text, or its file as UTF-8 with malformed bytes replaced. */
  @WorkerThread
  private static ChecksumList read(Request request, final Feed feed) throws IOException {
    final ChecksumList.Parser parser = new ChecksumList.Parser();
    TextSnapshot text = request.text();
    if (text != null) {
      text.readChunks(
          0,
          text.length(),
          new ChunkVisitor() {
            @Override
            public void chunk(CharSequence chunk, int from, int to) {
              feed.token.check();
              parser.add(chunk, from, to);
            }
          });
      return parser.finish();
    }
    try (Reader reader =
        new InputStreamReader(
            Files.newInputStream(request.file()),
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE))) {
      char[] buffer = new char[8192];
      while (true) {
        feed.token.check();
        int n = reader.read(buffer);
        if (n < 0) {
          break;
        }
        parser.add(CharBuffer.wrap(buffer, 0, n), 0, n);
      }
    }
    return parser.finish();
  }
}
