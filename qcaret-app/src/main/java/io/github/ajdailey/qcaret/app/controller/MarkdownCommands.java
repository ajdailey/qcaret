package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.config.PreviewRenderer;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.view.MainView;
import io.github.ajdailey.qcaret.app.view.MarkdownViewMode;

/**
 * View > Markdown View and Cycle Markdown View for one window, and the status bar's renderer field.
 * The view mode belongs to the selected tab; the renderer is {@code markdown.previewRenderer},
 * shared by every window. Browser draws only where {@link MainView#previewBrowserUnavailable} gives
 * no reason; a saved Browser choice otherwise shows Basic, and the first preview the window shows
 * posts a notice saying so. Confined to the UI thread.
 */
final class MarkdownCommands {
  private final Settings settings;
  private final DocumentController documents;
  private final MainView view;
  private boolean fallbackNoticed;

  MarkdownCommands(Settings settings, DocumentController documents, MainView view) {
    this.settings = settings;
    this.documents = documents;
    this.view = view;
  }

  /** Runs a Markdown View command or Cycle Markdown View; a mode change needs a Markdown tab. */
  void execute(CommandId command) {
    switch (command) {
      case MARKDOWN_SOURCE -> setMode(MarkdownViewMode.SOURCE);
      case MARKDOWN_SPLIT -> setMode(MarkdownViewMode.SPLIT);
      case MARKDOWN_PREVIEW -> setMode(MarkdownViewMode.PREVIEW);
      case MARKDOWN_CYCLE -> {
        if (documents.activeMarkdown()) {
          setMode(next(documents.markdownMode()));
        }
      }
      case PREVIEW_BASIC -> choose(PreviewRenderer.BASIC);
      case PREVIEW_BROWSER -> {
        if (view.previewBrowserUnavailable() == null) {
          choose(PreviewRenderer.BROWSER);
        }
      }
      default -> throw new IllegalArgumentException(command.name());
    }
  }

  private void setMode(MarkdownViewMode mode) {
    if (documents.setMarkdownMode(mode)) {
      if (mode != MarkdownViewMode.SOURCE
          && !fallbackNoticed
          && settings.current().options().previewRenderer() != renderer()) {
        fallbackNoticed = true;
        view.showNotice(UiText.MARKDOWN_BROWSER_FALLBACK.text());
      }
      view.documentStateChanged();
    }
  }

  private static MarkdownViewMode next(MarkdownViewMode mode) {
    return switch (mode) {
      case SOURCE -> MarkdownViewMode.SPLIT;
      case SPLIT -> MarkdownViewMode.PREVIEW;
      case PREVIEW -> MarkdownViewMode.SOURCE;
    };
  }

  private void choose(PreviewRenderer renderer) {
    settings.set(
        settings.current().withOptions(settings.current().options().withPreviewRenderer(renderer)));
    view.documentStateChanged();
  }

  /**
   * Returns the renderer previews draw with.
   *
   * @return the saved choice, or Basic while Browser cannot run
   */
  PreviewRenderer renderer() {
    PreviewRenderer chosen = settings.current().options().previewRenderer();
    return chosen == PreviewRenderer.BROWSER && view.previewBrowserUnavailable() != null
        ? PreviewRenderer.BASIC
        : chosen;
  }

  /** {@link CommandStates#checked} for a Markdown command. */
  boolean checked(CommandId command) {
    return switch (command) {
      case MARKDOWN_SOURCE -> documents.markdownMode() == MarkdownViewMode.SOURCE;
      case MARKDOWN_SPLIT -> documents.markdownMode() == MarkdownViewMode.SPLIT;
      case MARKDOWN_PREVIEW -> documents.markdownMode() == MarkdownViewMode.PREVIEW;
      case PREVIEW_BASIC -> renderer() == PreviewRenderer.BASIC;
      case PREVIEW_BROWSER -> renderer() == PreviewRenderer.BROWSER;
      default -> false;
    };
  }

  /** {@link CommandStates#enabled} for a Markdown command: the modes need a Markdown tab. */
  boolean enabled(CommandId command) {
    return switch (command) {
      case PREVIEW_BASIC -> true;
      case PREVIEW_BROWSER -> view.previewBrowserUnavailable() == null;
      default -> documents.activeMarkdown();
    };
  }

  /** {@link CommandStates#unavailable} for a Markdown command. */
  String unavailable(CommandId command) {
    return command == CommandId.PREVIEW_BROWSER ? view.previewBrowserUnavailable() : null;
  }

  /**
   * Returns the status bar's preview field.
   *
   * @return the renderer's name while the selected tab shows a preview, else empty
   */
  String status() {
    if (documents.markdownMode() == MarkdownViewMode.SOURCE) {
      return "";
    }
    UiText name =
        renderer() == PreviewRenderer.BROWSER
            ? UiText.PREVIEW_RENDERER_BROWSER
            : UiText.PREVIEW_RENDERER_BASIC;
    return UiText.STATUS_PREVIEW.format(name.text());
  }
}
