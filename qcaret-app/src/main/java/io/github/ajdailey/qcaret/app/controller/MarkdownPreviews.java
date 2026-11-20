package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.view.ActivityIndicator;
import io.github.ajdailey.qcaret.app.view.EditorView;
import io.github.ajdailey.qcaret.app.view.MainView;
import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.markdown.MarkdownRenderer;
import io.github.ajdailey.qcaret.core.syntax.GrammarSet;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * The {@link MarkdownPreview} of each tab of one {@link DocumentController}, created on first use.
 * A tab is a Markdown tab while its grammar is {@code markdown}. Confined to the UI thread.
 */
final class MarkdownPreviews {
  /** The grammar id of a Markdown tab. */
  static final String MARKDOWN = "markdown";

  /** The longest document, in chars, rendered on a switch or an edit. */
  static final int LIVE_LIMIT_CHARS = 1024 * 1024;

  private final Workers workers;
  private final UiDispatcher ui;
  private final MarkdownRenderer renderer = new MarkdownRenderer();
  private final DocumentGrammars grammars;
  private final MainView view;
  private final Consumer<Path> openFile;

  /**
   * Creates the previews of one window.
   *
   * @param workers runs renders
   * @param ui receives results
   * @param grammars the window's grammars and highlight line limit
   * @param view opens URLs in the system browser
   * @param openFile opens a local file in the window
   */
  MarkdownPreviews(
      Workers workers,
      UiDispatcher ui,
      DocumentGrammars grammars,
      MainView view,
      Consumer<Path> openFile) {
    this.workers = workers;
    this.ui = ui;
    this.grammars = grammars;
    this.view = view;
    this.openFile = openFile;
  }

  /** Returns a tab's preview, creating it in Source. */
  MarkdownPreview of(final OpenDocument d) {
    if (d.markdown == null) {
      d.markdown =
          new MarkdownPreview(
              d.document,
              workers,
              ui,
              renderer,
              new MarkdownPreview.Tab() {
                @Override
                public Path folder() {
                  return d.file.path == null ? null : d.file.path.getParent();
                }

                @Override
                public ActivityIndicator busy() {
                  return d.editor.busyCursor();
                }

                @Override
                public GrammarSet grammars() {
                  return grammars.current();
                }

                @Override
                public int maxLineChars() {
                  return grammars.maxLineChars();
                }

                @Override
                public void scrollSource(int line) {
                  EditorView view = d.editor.view();
                  if (view != null) {
                    view.scrollToRow(d.editor.projection().rowOfLine(line));
                  }
                }

                @Override
                public void openFile(Path path) {
                  openFile.accept(path);
                }

                @Override
                public void openUrl(String url) {
                  view.launchUrl(url);
                }
              },
              LIVE_LIMIT_CHARS);
      d.markdown.grammarChanged(MARKDOWN.equals(d.grammar));
    }
    return d.markdown;
  }

  /** Gives a tab moved in from another window the mode it had there. */
  void adopt(OpenDocument d, OpenDocument from) {
    if (from.markdown != null) {
      of(d).setMode(from.markdown.mode());
    }
  }

  /** Disposes a closing or moving tab's preview. */
  void closed(OpenDocument d) {
    if (d.markdown != null) {
      d.markdown.dispose();
    }
  }
}
