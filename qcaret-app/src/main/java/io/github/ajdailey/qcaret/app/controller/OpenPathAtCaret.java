package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.view.MainView;
import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import io.github.ajdailey.qcaret.core.buffer.TextSnapshot;
import io.github.ajdailey.qcaret.core.concurrent.WorkerThread;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.markdown.HeadingPath;
import io.github.ajdailey.qcaret.core.text.PathAtCaret;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * File > Open Path at Caret: finds the path at the primary caret ({@link PathAtCaret}) within
 * {@link #REACH} chars either side of it, resolves a relative path against the document's folder,
 * or the workspace folder for an untitled document, and opens the file through {@link
 * DocumentController#open(Path, Consumer)}, going to its {@code :line:column}, or to the Markdown
 * heading a {@code file.md#id} path names when no file has the whole name ({@link
 * HeadingPath#resolve}). The search and the file checks run on a {@code qcaret-io-} worker under
 * the busy cursor. No path, a URL or no regular file shows a notice; a newer run drops an older
 * result. Confined to the UI thread.
 */
final class OpenPathAtCaret {
  /** Chars read on each side of the caret. */
  static final int REACH = 4096;

  /**
   * What a check found.
   *
   * @param file the regular file to open; null with a notice
   * @param found the path as written, with its line and column; null with a notice
   * @param notice why nothing opens; null with a file
   * @param heading the id of the heading the caret goes to, or null
   */
  record Target(Path file, PathAtCaret.Found found, String notice, String heading) {
    Target(Path file, PathAtCaret.Found found, String notice) {
      this(file, found, notice, null);
    }
  }

  private final DocumentController documents;
  private final Supplier<Path> workspace;
  private final MainView view;
  private final Workers workers;
  private final UiDispatcher ui;
  private int generation;

  OpenPathAtCaret(
      DocumentController documents,
      Supplier<Path> workspace,
      MainView view,
      Workers workers,
      UiDispatcher ui) {
    this.documents = documents;
    this.workspace = workspace;
    this.view = view;
    this.workers = workers;
    this.ui = ui;
  }

  /** Looks for the path at the selected editor's caret and opens its file. */
  void open() {
    EditorController editor = documents.activeEditor();
    if (editor == null) {
      return;
    }
    final TextSnapshot text = editor.document().text();
    final int caret = editor.selections().selections().primary().caret();
    Path file = documents.activePath();
    final Path base = file == null ? workspace.get() : file.toAbsolutePath().getParent();
    final int run = ++generation;
    new BusyWork(ui, view) {
      private Target target;

      @Override
      @WorkerThread
      void work() {
        target = find(text, caret, base);
      }

      @Override
      void finished(boolean completed) {
        if (completed && run == generation) {
          opened(target);
        }
      }
    }.start(workers, Workers.Kind.IO);
  }

  private void opened(Target target) {
    if (target.notice() != null) {
      view.showNotice(target.notice());
      return;
    }
    final PathAtCaret.Found found = target.found();
    final String heading = target.heading();
    documents.open(
        target.file(),
        new Consumer<EditorController>() {
          @Override
          public void accept(EditorController editor) {
            if (found.line() > 0) {
              editor.goTo(found.line() - 1, Math.max(0, found.column() - 1));
            } else if (heading != null) {
              documents.jumpToHeading(editor, heading);
            }
          }
        });
  }

  /**
   * Finds and checks the path at a caret.
   *
   * @param text the document's text
   * @param caret the caret's offset
   * @param base the folder a relative path is resolved against, or null for none
   * @return the file, or the notice to show
   */
  @WorkerThread
  static Target find(TextSnapshot text, int caret, Path base) {
    int line = text.lineAt(caret);
    int start = text.offsetOfLine(line);
    int end = start + text.lineLength(line);
    int from = Math.max(start, caret - REACH);
    int to = Math.min(end, caret + REACH);
    PathAtCaret.Found found = PathAtCaret.find(text.subSequence(from, to), caret - from);
    if (found == null) {
      return new Target(null, null, UiText.OPEN_PATH_NONE.text());
    }
    if (found.url()) {
      return new Target(null, null, UiText.OPEN_PATH_URL.format(found.path()));
    }
    Path path;
    try {
      path = Path.of(found.path());
    } catch (InvalidPathException e) {
      return new Target(null, null, UiText.OPEN_PATH_MISSING.format(found.path()));
    }
    if (!path.isAbsolute()) {
      if (base == null) {
        return new Target(null, null, UiText.OPEN_PATH_MISSING.format(found.path()));
      }
      path = base.resolve(path);
    }
    path = path.normalize();
    if (!Files.isRegularFile(path)) {
      HeadingPath split = HeadingPath.resolve(path);
      if (split.heading() != null) {
        return new Target(split.path(), found, null, split.heading());
      }
      return new Target(null, null, UiText.OPEN_PATH_MISSING.format(path));
    }
    return new Target(path, found, null);
  }
}
