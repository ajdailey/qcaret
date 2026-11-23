package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.theme.Palette;
import io.github.ajdailey.qcaret.app.view.ActivityIndicator;
import io.github.ajdailey.qcaret.app.view.EditorView;
import io.github.ajdailey.qcaret.app.view.MainView;
import io.github.ajdailey.qcaret.app.view.MarkdownViewMode;
import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.markdown.MarkdownRenderer;
import io.github.ajdailey.qcaret.core.syntax.GrammarSet;
import java.nio.file.Path;
import java.util.function.BiConsumer;

/**
 * The {@link MarkdownPreview} of each tab of one {@link DocumentController}, created on first use
 * with its tab's pane attached. A tab is a Markdown tab while its grammar is {@code markdown}. In
 * Split with scroll sync on, the tab's focused editor reports its top line through {@link
 * #sourceScrolled}. A web link asks before it opens ({@link MainView#askOpenUrl}). Confined to the
 * UI thread.
 */
final class MarkdownPreviews {
  /** The grammar id of a Markdown tab. */
  static final String MARKDOWN = "markdown";

  private static final String SPLIT = "split";
  private static final String PREVIEW = "preview";
  private static final String SOURCE = "source";

  /** The longest document, in chars, rendered on a switch or an edit. */
  static final int LIVE_LIMIT_CHARS = 1024 * 1024;

  private final Workers workers;
  private final UiDispatcher ui;
  private final MarkdownRenderer renderer = new MarkdownRenderer();
  private final DocumentGrammars grammars;
  private final MainView view;
  private final BiConsumer<Path, String> openFile;
  private final MemoryBudget budget;
  private final MarkdownImages images;
  private boolean browserChosen;
  private boolean syncScroll = true;
  private Palette palette = Palette.LIGHT;
  private int zoom = ZoomCommands.NORMAL;
  private MarkdownViewMode openMode = MarkdownViewMode.SPLIT;

  /**
   * Creates the previews of one window.
   *
   * @param workers runs renders
   * @param ui receives results
   * @param grammars the window's grammars and highlight line limit
   * @param view opens URLs in the system browser and shows notices
   * @param openFile opens a local file in the window, with the caret at a heading or null
   * @param budget charged for a render above {@link #LIVE_LIMIT_CHARS}
   */
  MarkdownPreviews(
      Workers workers,
      UiDispatcher ui,
      DocumentGrammars grammars,
      MainView view,
      BiConsumer<Path, String> openFile,
      MemoryBudget budget) {
    this.workers = workers;
    this.ui = ui;
    this.grammars = grammars;
    this.view = view;
    this.openFile = openFile;
    this.budget = budget;
    this.images = new MarkdownImages(workers, ui);
  }

  /** Returns a tab's preview, creating it in Source with the tab's pane attached. */
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
                public boolean syncScroll() {
                  return syncScroll;
                }

                @Override
                public int scrollSource(int line) {
                  EditorView editor = d.editor.view();
                  if (editor == null) {
                    return line;
                  }
                  editor.scrollToRow(d.editor.projection().rowOfLine(line));
                  return d.editor.projection().lineOfRow(editor.topRow());
                }

                @Override
                public void notice(String message) {
                  view.showNotice(message);
                }

                @Override
                public void openFile(Path path, String heading) {
                  openFile.accept(path, heading);
                }

                @Override
                public void openUrl(final String url) {
                  view.askOpenUrl(
                      url,
                      new Runnable() {
                        @Override
                        public void run() {
                          view.launchUrl(url);
                        }
                      });
                }

                @Override
                public boolean browser() {
                  return browserChosen && view.previewBrowserUnavailable() == null;
                }

                @Override
                public Palette palette() {
                  return palette;
                }

                @Override
                public int previewZoom() {
                  return zoom;
                }

                @Override
                public void browserFailed() {
                  view.showNotice(UiText.MARKDOWN_BROWSER_FALLBACK.text());
                  view.documentStateChanged();
                }
              },
              LIVE_LIMIT_CHARS,
              budget,
              images);
      d.markdown.grammarChanged(MARKDOWN.equals(d.grammar));
      if (d.tab != null) {
        d.markdown.attach(d.tab.markdownView(d.markdown));
      }
    }
    return d.markdown;
  }

  /**
   * Sets the renderer chosen in {@code markdown.previewRenderer}; Browser draws only while {@link
   * MainView#previewBrowserUnavailable} gives no reason.
   *
   * @param browser true for Browser
   * @return true when the choice changed
   */
  boolean setBrowserChosen(boolean browser) {
    boolean changed = browserChosen != browser;
    browserChosen = browser;
    return changed;
  }

  /**
   * Sets whether Split scrolls a tab's editor and preview together ({@code markdown.syncScroll}).
   *
   * @param on true to sync
   */
  void setSyncScroll(boolean on) {
    syncScroll = on;
  }

  /**
   * Sets the palette of Browser pages.
   *
   * @param next the window's palette
   * @return true when it changed
   */
  boolean setPalette(Palette next) {
    boolean changed = !palette.equals(next);
    palette = next;
    return changed;
  }

  /**
   * Sets the previews' text size ({@code markdown.previewZoom}).
   *
   * @param percent the size in percent
   * @return true when it changed
   */
  boolean setZoom(int percent) {
    boolean changed = zoom != percent;
    zoom = percent;
    return changed;
  }

  /**
   * Sets the mode of a Markdown file opened with no saved mode ({@code markdown.openMode}).
   *
   * @param mode the mode
   */
  void setOpenMode(MarkdownViewMode mode) {
    openMode = mode;
  }

  /**
   * Gives a tab with no saved pick the open mode: at once on a Markdown tab, else after its grammar
   * is detected ({@link #grammarApplied}) when that grammar is {@code markdown}. A tab the user
   * already picked a mode for keeps it.
   *
   * @param d the tab
   */
  void opened(OpenDocument d) {
    d.markdownOpening = false;
    if (d.markdownPicked || openMode == MarkdownViewMode.SOURCE) {
      return;
    }
    if (MARKDOWN.equals(d.grammar)) {
      of(d).setMode(openMode);
    } else {
      d.markdownOpening = true;
    }
  }

  /**
   * Applies the open mode to a tab waiting for it, once its grammar is applied; any grammar ends
   * the wait.
   *
   * @param d the tab
   */
  void grammarApplied(OpenDocument d) {
    d.markdownOpening = false;
    if (MARKDOWN.equals(d.grammar) && openMode != MarkdownViewMode.SOURCE) {
      of(d).setMode(openMode);
    }
  }

  /** Gives a tab moved in from another window the mode it had there. */
  void adopt(OpenDocument d, OpenDocument from) {
    d.markdownPicked = from.markdownPicked;
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

  /**
   * Applies a tab's saved pick once its grammar is known; the tab keeps it as its pick.
   *
   * @param d the tab
   * @param saved the mode as {@link #saved} wrote it, not empty
   */
  void restore(OpenDocument d, String saved) {
    d.markdownOpening = false;
    d.markdownPicked = true;
    MarkdownViewMode mode = parse(saved);
    if (mode != MarkdownViewMode.SOURCE || d.markdown != null) {
      of(d).restoreMode(mode, d.detecting > 0 || grammars.current() == null);
    }
  }

  /**
   * Returns a tab's mode as the session and the file memory keep it: the user's pick only.
   *
   * @param d the tab
   * @return {@code source}, {@code split} or {@code preview}; empty while the user has picked none,
   *     so the next open takes {@code markdown.openMode}
   */
  static String saved(OpenDocument d) {
    if (!d.markdownPicked) {
      return "";
    }
    MarkdownViewMode mode = d.markdown == null ? MarkdownViewMode.SOURCE : d.markdown.mode();
    return switch (mode) {
      case SPLIT -> SPLIT;
      case PREVIEW -> PREVIEW;
      case SOURCE -> SOURCE;
    };
  }

  /** Parses a saved mode; anything else, empty included, is Source. */
  static MarkdownViewMode parse(String saved) {
    return switch (saved) {
      case SPLIT -> MarkdownViewMode.SPLIT;
      case PREVIEW -> MarkdownViewMode.PREVIEW;
      default -> MarkdownViewMode.SOURCE;
    };
  }

  /**
   * Reports an editor's new top line; the preview of its tab follows in Split while scroll sync is
   * on.
   *
   * @param d the editor's tab
   * @param editor the editor
   */
  void sourceScrolled(OpenDocument d, EditorController editor) {
    if (syncScroll
        && d.markdown != null
        && editor == d.editor
        && d.markdown.mode() == MarkdownViewMode.SPLIT) {
      d.markdown.sourceScrolled(d.document.text().lineAt(editor.visibleStart()));
    }
  }
}
