package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.view.MainView;
import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import io.github.ajdailey.qcaret.core.concurrent.WorkerThread;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.io.ChecksumAlgorithm;
import io.github.ajdailey.qcaret.core.io.ChecksumList;
import io.github.ajdailey.qcaret.core.io.Checksums;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.LongConsumer;

/**
 * Tools > Generate from Files: hashes chosen files one after another into {@code sha256sum} lines,
 * {@code <hex> <name>}, each shown as its file ends. A name is the file name, or the full path
 * where two chosen files share a name. A file that cannot be read gets no line; the status line
 * counts it and names the first one. Choose Files starts a new run.
 */
final class FileChecksums extends ChecksumRun<FileChecksums.Request> {
  private final ChecksumAlgorithm algorithm;

  /**
   * A run's files.
   *
   * @param algorithm the digest
   * @param files the files, in the chosen order
   * @param names each file's name in the list
   */
  record Request(ChecksumAlgorithm algorithm, List<Path> files, List<String> names) {}

  FileChecksums(ChecksumAlgorithm algorithm, Workers workers, UiDispatcher ui, MainView view) {
    super(workers, ui, view);
    this.algorithm = algorithm;
  }

  /**
   * Opens the dialog and hashes files.
   *
   * @param files the chosen files
   */
  void open(List<Path> files) {
    openDialog(
        UiText.CHECKSUM_FILES_TITLE.format(algorithm.javaName()),
        ChecksumTools.note(algorithm, ""),
        UiText.BUTTON_CHOOSE_FILES.text());
    run(files);
  }

  private void run(List<Path> files) {
    Path parent = files.get(0).toAbsolutePath().getParent();
    for (Path file : files) {
      if (parent != null && !parent.equals(file.toAbsolutePath().getParent())) {
        parent = null;
      }
    }
    start(
        parent == null ? "" : parent.toString(),
        files.get(0).toAbsolutePath().getParent(),
        new Request(algorithm, List.copyOf(files), names(files)));
  }

  /**
   * Returns each file's name in the list: its file name, or its full path where names repeat.
   *
   * @param files the files
   * @return the names, in order
   */
  static List<String> names(List<Path> files) {
    Map<String, Integer> counts = new HashMap<String, Integer>();
    for (Path file : files) {
      counts.merge(String.valueOf(file.getFileName()), 1, Integer::sum);
    }
    List<String> out = new ArrayList<String>(files.size());
    for (Path file : files) {
      String name = String.valueOf(file.getFileName());
      out.add(counts.get(name) > 1 ? file.toAbsolutePath().toString() : name);
    }
    return out;
  }

  @Override
  String saveName() {
    return algorithm.javaName().replace("-", "") + "SUMS";
  }

  @Override
  public void choose() {
    view.chooseFilesToOpen(
        folder(),
        new Consumer<List<Path>>() {
          @Override
          public void accept(List<Path> files) {
            if (!files.isEmpty() && display() != null) {
              run(files);
            }
          }
        });
  }

  @Override
  @WorkerThread
  void work(Request request, final Feed feed) {
    long total = 0;
    for (Path file : request.files()) {
      try {
        total += Files.size(file);
      } catch (IOException e) {
        // Counted as zero bytes; the read reports the failure.
      }
    }
    final long all = total;
    final long[] done = {0};
    int hashed = 0;
    int notRead = 0;
    String firstFailure = null;
    for (int i = 0; i < request.files().size(); i++) {
      final String name = request.names().get(i);
      final long base = done[0];
      feed.progress(name, base, all);
      try {
        String hex =
            Checksums.ofFile(
                request.files().get(i),
                ChecksumTools.digest(request.algorithm()),
                feed.token,
                new LongConsumer() {
                  @Override
                  public void accept(long read) {
                    done[0] = base + read;
                    feed.progress(name, done[0], all);
                  }
                });
        feed.line(ChecksumList.line(hex, name));
        hashed++;
      } catch (IOException e) {
        notRead++;
        if (firstFailure == null) {
          firstFailure = UiText.CHECKSUM_NOT_READ.format(name, reason(e));
        }
      }
    }
    String totals = UiText.CHECKSUM_FILES_DONE.format(hashed, notRead);
    feed.finish(firstFailure == null ? totals : totals + " " + firstFailure);
  }
}
