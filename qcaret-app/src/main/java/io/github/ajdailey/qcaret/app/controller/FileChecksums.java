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
import java.util.List;
import java.util.function.Consumer;
import java.util.function.LongConsumer;

/**
 * Tools > Generate from Files: hashes chosen files one after another into {@code sha256sum} lines,
 * {@code <hex> <name>}, each shown as its file ends ({@link #names} gives the names). Save As
 * starts in the files' common folder, where Verify Checksums resolves the names. A file that cannot
 * be read gets no line; the status line counts it and names the first one. Choose Files starts a
 * new run.
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

  FileChecksums(
      ChecksumAlgorithm algorithm,
      Workers workers,
      UiDispatcher ui,
      MainView view,
      DocumentsUnder under,
      RunningWrites writes) {
    super(workers, ui, view, under, writes);
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
    Path common = files.get(0).toAbsolutePath().getParent();
    for (Path file : files) {
      while (common != null && !file.toAbsolutePath().startsWith(common)) {
        common = common.getParent();
      }
    }
    List<List<String>> elements = new ArrayList<List<String>>(files.size());
    for (Path file : files) {
      elements.add(elements(file.toAbsolutePath()));
    }
    start(
        common == null ? "" : common.toString(),
        common == null ? files.get(0).toAbsolutePath().getParent() : common,
        new Request(algorithm, List.copyOf(files), names(elements)));
  }

  /** Returns a path's root, or an empty string without one, then each name element. */
  private static List<String> elements(Path path) {
    List<String> out = new ArrayList<String>();
    Path root = path.getRoot();
    out.add(root == null ? "" : root.toString());
    for (Path name : path) {
      out.add(name.toString());
    }
    return out;
  }

  /**
   * Returns each file's name in the list: the path below the files' common folder, which Save As
   * offers, its elements joined by {@code /} on every platform. Files in one folder get their file
   * names. Files with no common folder (two Windows drives) keep the root and every element.
   *
   * @param elements each file's root, then its name elements, as absolute paths
   * @return the names, in order
   */
  static List<String> names(List<List<String>> elements) {
    List<String> out = new ArrayList<String>(elements.size());
    int common = commonPrefix(elements);
    for (List<String> file : elements) {
      String joined = String.join("/", file.subList(Math.max(common, 1), file.size()));
      out.add(common == 0 ? file.get(0) + joined : joined);
    }
    return out;
  }

  /** Returns how many leading elements, root included, every file shares, short of a file name. */
  private static int commonPrefix(List<List<String>> elements) {
    List<String> first = elements.get(0);
    int common = first.size() - 1;
    for (List<String> file : elements) {
      int n = 0;
      while (n < common && n < file.size() - 1 && file.get(n).equals(first.get(n))) {
        n++;
      }
      common = n;
    }
    return common;
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
