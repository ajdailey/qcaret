package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.editor.MarkdownPage;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.theme.Palette;
import io.github.ajdailey.qcaret.app.view.MainView;
import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import io.github.ajdailey.qcaret.core.buffer.TextSnapshot;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.WorkerThread;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.io.ContentWriter;
import io.github.ajdailey.qcaret.core.io.SafeSave;
import io.github.ajdailey.qcaret.core.markdown.MarkdownRenderer;
import io.github.ajdailey.qcaret.core.markdown.MarkdownTree;
import io.github.ajdailey.qcaret.core.syntax.GrammarSet;
import io.github.ajdailey.qcaret.core.text.LogText;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * File > Export as HTML: writes a Markdown tab's text, unsaved edits included, as one HTML page
 * that opens offline, with the Light palette's stylesheet inline and local images as {@code data:}
 * URIs; remote images are links and links stay as written. The page parses, renders and writes
 * through {@link SafeSave} on a {@code qcaret-io-} worker under the busy cursor. The document's own
 * file is refused as the target. Confined to the UI thread.
 */
final class MarkdownExport {
  private final Workers workers;
  private final UiDispatcher ui;
  private final MainView view;
  private final DocumentGrammars grammars;
  private final MarkdownRenderer renderer = new MarkdownRenderer();

  MarkdownExport(Workers workers, UiDispatcher ui, MainView view, DocumentGrammars grammars) {
    this.workers = workers;
    this.ui = ui;
    this.view = view;
    this.grammars = grammars;
  }

  /** Asks for a file and writes the tab's page to it. */
  void export(final OpenDocument d) {
    Path directory = d.file.path == null ? null : d.file.path.getParent();
    view.chooseSaveTarget(
        directory,
        htmlName(d.name()),
        new Consumer<Path>() {
          @Override
          public void accept(Path target) {
            if (target != null && !d.closed) {
              write(d, target);
            }
          }
        });
  }

  /**
   * Returns the proposed file name: the document's name with {@code .html} in place of its
   * extension.
   */
  static String htmlName(String name) {
    int dot = name.lastIndexOf('.');
    return (dot > 0 ? name.substring(0, dot) : name) + ".html";
  }

  private void write(final OpenDocument d, final Path target) {
    final Path own = d.file.path;
    if (own != null
        && target.toAbsolutePath().normalize().equals(own.toAbsolutePath().normalize())) {
      view.showInfo(UiText.EXPORT_HTML_TITLE.text(), UiText.EXPORT_HTML_SOURCE.format(d.name()));
      return;
    }
    final TextSnapshot text = d.document.text();
    final String title = d.name();
    final Path folder = own == null ? null : own.getParent();
    final GrammarSet set = grammars.current();
    final int maxLineChars = grammars.maxLineChars();
    new BusyWork(ui, view) {
      private IOException error;

      @Override
      @WorkerThread
      void work() {
        try {
          byte[] page = page(renderer, text, title, folder, set, maxLineChars);
          SafeSave.save(target, null, bytes(page), new CancellationToken());
        } catch (IOException e) {
          error = e;
        }
      }

      @Override
      void finished(boolean completed) {
        if (error != null) {
          view.showInfo(
              UiText.EXPORT_HTML_TITLE.text(),
              UiText.EXPORT_HTML_FAILED.format(
                  String.valueOf(target.getFileName()), LogText.oneLine(error.getMessage())));
        } else if (completed) {
          view.showNotice(UiText.EXPORT_HTML_DONE.format(title, target));
        }
      }
    }.start(workers, Workers.Kind.IO);
  }

  /**
   * Builds the exported page.
   *
   * @param renderer parses the text
   * @param text the Markdown source
   * @param title the page title
   * @param folder the document's folder, or null for an untitled document
   * @param grammars the grammars code is colored with, or null
   * @param maxLineChars a longer code line stays plain
   * @return the page in UTF-8
   */
  @WorkerThread
  static byte[] page(
      MarkdownRenderer renderer,
      CharSequence text,
      String title,
      Path folder,
      GrammarSet grammars,
      int maxLineChars) {
    CancellationToken never = new CancellationToken();
    MarkdownTree tree = renderer.parse(text, never);
    String body = MarkdownHtmlWriter.export(tree, folder, grammars, maxLineChars, never).body();
    return MarkdownPage.page(title, body, Palette.LIGHT).getBytes(StandardCharsets.UTF_8);
  }

  private static ContentWriter bytes(final byte[] page) {
    return new ContentWriter() {
      @Override
      public void write(OutputStream out) throws IOException {
        out.write(page);
      }
    };
  }
}
