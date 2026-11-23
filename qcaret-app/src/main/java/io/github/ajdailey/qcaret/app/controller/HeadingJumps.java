package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.view.EditorView;
import io.github.ajdailey.qcaret.app.view.MainView;
import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import io.github.ajdailey.qcaret.core.buffer.TextSnapshot;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.WorkerThread;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.markdown.HeadingPath;
import io.github.ajdailey.qcaret.core.markdown.MarkdownRenderer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BiConsumer;

/**
 * Moves a tab's caret to the Markdown heading a {@code file.md#id} target names, for one {@link
 * DocumentController}. Confined to the UI thread.
 *
 * <ul>
 *   <li>A tab whose grammar is not known yet (grammars unread, detection running) waits for {@link
 *       #grammarApplied}.
 *   <li>A tab whose grammar is not {@code markdown} ignores the id, with the notice an unknown id
 *       shows.
 *   <li>A heading pass parses a text snapshot on a {@code qcaret-cpu-} worker ({@link
 *       io.github.ajdailey.qcaret.core.markdown.MarkdownTree#headingSourceLine}), with the
 *       preview's GitLab ids and without rendering. A document over {@link
 *       MarkdownPreviews#LIVE_LIMIT_CHARS} chars is not parsed and shows a notice.
 *   <li>The UI thread applies the line: the caret at its start, the line at the editor's top, and
 *       outside Source the preview scrolled to it ({@link MarkdownPreview#revealSource}). An id no
 *       heading has puts the caret on the first line and shows a notice naming the id.
 *   <li>A tab holds one jump: a later jump to it replaces the earlier. A tab leaving the window
 *       drops its jump ({@link #closed}); a pass whose document was edited since the snapshot is
 *       dropped.
 *   <li>A preview link's {@code file.md#id} opens a file named {@code file.md#id} with no heading
 *       when one exists ({@link #openLink}).
 * </ul>
 */
final class HeadingJumps {
  private final Workers workers;
  private final UiDispatcher ui;
  private final MainView view;
  private final DocumentGrammars grammars;
  private final BiConsumer<Path, String> opener;
  private final MarkdownRenderer renderer = new MarkdownRenderer();
  private final Map<OpenDocument, Jump> pending = new IdentityHashMap<OpenDocument, Jump>();

  /** One jump: its tab, editor and id, and once its pass ends, the heading's line. */
  private static final class Jump {
    final OpenDocument d;
    final EditorController editor;
    final String id;
    boolean started;
    long revision;
    volatile int line = -1;
    volatile boolean done;

    Jump(OpenDocument d, EditorController editor, String id) {
      this.d = d;
      this.editor = editor;
      this.id = id;
    }
  }

  /**
   * Creates the jumps of one window.
   *
   * @param workers runs heading passes and file checks
   * @param ui receives results
   * @param view shows notices
   * @param grammars the window's grammars
   * @param opener opens a file in the window, with the caret at a heading or null
   */
  HeadingJumps(
      Workers workers,
      UiDispatcher ui,
      MainView view,
      DocumentGrammars grammars,
      BiConsumer<Path, String> opener) {
    this.workers = workers;
    this.ui = ui;
    this.view = view;
    this.grammars = grammars;
    this.opener = opener;
  }

  /**
   * Opens a link's file at its heading. With a heading, a {@code qcaret-io-} worker first checks
   * for a regular file named with the {@code #heading} ({@link HeadingPath#whole}), which opens
   * with no heading.
   *
   * @param path the file before the {@code #}
   * @param heading the fragment, or null
   */
  void openLink(final Path path, final String heading) {
    if (heading == null) {
      opener.accept(path, null);
      return;
    }
    workers.tryStart(
        Workers.Kind.IO,
        new Runnable() {
          @Override
          public void run() {
            Path whole = HeadingPath.whole(path, heading);
            final boolean named = whole != null && Files.isRegularFile(whole);
            final Path file = named ? whole : path;
            ui.asyncExec(
                new Runnable() {
                  @Override
                  public void run() {
                    opener.accept(file, named ? null : heading);
                  }
                });
          }
        });
  }

  /**
   * Moves an editor's caret to a heading of its tab.
   *
   * @param d the tab
   * @param editor the tab's editor that moves
   * @param id the heading's GitLab id
   */
  void jump(OpenDocument d, EditorController editor, String id) {
    Jump jump = new Jump(d, editor, id);
    pending.put(d, jump);
    if (known(d)) {
      start(jump);
    }
  }

  /**
   * Starts the jump waiting for a tab's grammar.
   *
   * @param d the tab whose grammar was applied
   */
  void grammarApplied(OpenDocument d) {
    Jump jump = pending.get(d);
    if (jump != null && !jump.started && known(d)) {
      start(jump);
    }
  }

  /**
   * Drops the jump of a tab leaving the window.
   *
   * @param d the tab
   */
  void closed(OpenDocument d) {
    pending.remove(d);
  }

  private boolean known(OpenDocument d) {
    return grammars.current() != null && d.detecting == 0;
  }

  private void start(final Jump jump) {
    jump.started = true;
    OpenDocument d = jump.d;
    if (d.closed || !MarkdownPreviews.MARKDOWN.equals(d.grammar)) {
      pending.remove(d);
      if (!d.closed) {
        view.showNotice(UiText.MARKDOWN_NO_HEADING.format(jump.id, d.name()));
      }
      return;
    }
    final TextSnapshot text = d.document.text();
    if (text.length() > MarkdownPreviews.LIVE_LIMIT_CHARS) {
      pending.remove(d);
      view.showNotice(
          UiText.MARKDOWN_HEADING_TOO_LARGE.format(
              d.name(), MarkdownPreviews.LIVE_LIMIT_CHARS, jump.id));
      return;
    }
    jump.revision = d.document.revision();
    try {
      workers.executeCpu(
          new Runnable() {
            @Override
            public void run() {
              try {
                find(jump, text);
              } finally {
                ui.asyncExec(
                    new Runnable() {
                      @Override
                      public void run() {
                        apply(jump);
                      }
                    });
              }
            }
          });
    } catch (RejectedExecutionException e) {
      pending.remove(d);
    }
  }

  @WorkerThread
  private void find(Jump jump, TextSnapshot text) {
    try {
      jump.line = renderer.parse(text, new CancellationToken()).headingSourceLine(jump.id);
      jump.done = true;
    } catch (CancellationException e) {
      jump.done = false;
    }
  }

  private void apply(Jump jump) {
    OpenDocument d = jump.d;
    if (pending.get(d) != jump) {
      return;
    }
    pending.remove(d);
    if (!jump.done || d.closed || d.document.revision() != jump.revision) {
      return;
    }
    int line = jump.line;
    if (line < 0) {
      view.showNotice(UiText.MARKDOWN_NO_HEADING.format(jump.id, d.name()));
      line = 0;
    }
    EditorController editor = jump.editor;
    editor.goTo(line, 0);
    EditorView pane = editor.view();
    if (pane != null) {
      pane.scrollToRow(editor.projection().rowOfLine(line));
    }
    if (d.markdown != null) {
      d.markdown.revealSource(line);
    }
  }
}
