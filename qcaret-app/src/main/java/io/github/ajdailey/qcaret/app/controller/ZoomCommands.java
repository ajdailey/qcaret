package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.config.Options;
import io.github.ajdailey.qcaret.app.view.MainView;

/**
 * Zoom In, Zoom Out and Reset Zoom, and a wheel turn with Ctrl, for one window. A command acts on
 * the Markdown preview while {@link MainView#previewFocused} holds, else on the editor font ({@link
 * EditorZoom}). The preview's zoom is {@code markdown.previewZoom}, in steps of {@value #STEP}
 * percent from {@link Options#MIN_PREVIEW_ZOOM} to {@link Options#MAX_PREVIEW_ZOOM}; Reset Zoom
 * sets it to {@value #NORMAL}. Both zooms are settings, so every window follows. Confined to the UI
 * thread.
 */
final class ZoomCommands {
  /** Percent a preview zoom step adds or takes. */
  static final int STEP = 10;

  /** The preview's unzoomed size, in percent. */
  static final int NORMAL = 100;

  private final Settings settings;
  private final MainView view;

  ZoomCommands(Settings settings, MainView view) {
    this.settings = settings;
    this.view = view;
  }

  /**
   * Runs a zoom command on the focused pane.
   *
   * @param command {@link CommandId#ZOOM_IN}, {@link CommandId#ZOOM_OUT} or {@link
   *     CommandId#ZOOM_RESET}
   */
  void execute(CommandId command) {
    run(command, view.previewFocused());
  }

  /**
   * Zooms the pane a wheel turn with Ctrl went to, whatever has the focus.
   *
   * @param preview true for the Markdown preview, false for the editor
   * @param steps +1 zooms in, -1 out
   */
  void wheel(boolean preview, int steps) {
    run(steps > 0 ? CommandId.ZOOM_IN : CommandId.ZOOM_OUT, preview);
  }

  private void run(CommandId command, boolean preview) {
    Options options = settings.current().options();
    int steps =
        switch (command) {
          case ZOOM_IN -> 1;
          case ZOOM_OUT -> -1;
          default -> 0;
        };
    Options next;
    if (preview) {
      next = options.withPreviewZoom(steps == 0 ? NORMAL : stepped(options, steps));
    } else if (steps == 0) {
      next = options.withFontZoom(0);
    } else {
      next = EditorZoom.step(options, view.defaultFontSize(), steps);
    }
    settings.set(settings.current().withOptions(next));
    if (preview) {
      view.documentStateChanged();
    }
  }

  private static int stepped(Options options, int steps) {
    return Math.max(
        Options.MIN_PREVIEW_ZOOM,
        Math.min(Options.MAX_PREVIEW_ZOOM, options.previewZoom() + steps * STEP));
  }

  /**
   * Reports whether a zoom command's item is enabled: Zoom In below the focused pane's largest
   * size, Zoom Out above its smallest.
   *
   * @param command a zoom command
   * @return true when the command changes the focused pane's size
   */
  boolean enabled(CommandId command) {
    Options options = settings.current().options();
    boolean preview = view.previewFocused();
    int now = preview ? options.previewZoom() : EditorZoom.points(options, view.defaultFontSize());
    return switch (command) {
      case ZOOM_IN -> now < (preview ? Options.MAX_PREVIEW_ZOOM : Options.MAX_FONT_SIZE);
      case ZOOM_OUT -> now > (preview ? Options.MIN_PREVIEW_ZOOM : Options.MIN_FONT_SIZE);
      default -> true;
    };
  }
}
