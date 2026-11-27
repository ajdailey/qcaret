package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.view.HexMode;
import io.github.ajdailey.qcaret.app.view.MainView;
import io.github.ajdailey.qcaret.app.view.MarkdownViewMode;
import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import io.github.ajdailey.qcaret.core.concurrent.Workers;

/**
 * The hex panes of one {@link DocumentController}'s tabs ({@link HexTab}), created on first use. A
 * tab shows its hex pane or its Markdown preview, never both: leaving Text sets the Markdown mode
 * to Source, and a Markdown mode other than Source sets Text ({@link #markdownShown}). The session
 * and the file memory keep a mode the user picked as {@code text}, {@code split} or {@code hex}.
 * Confined to the UI thread.
 */
final class HexPanes {
  private static final String TEXT = "text";
  private static final String SPLIT = "split";
  private static final String HEX = "hex";

  private final Workers workers;
  private final UiDispatcher ui;
  private final MainView view;
  private final HexViewers viewers;
  private int bytesPerRow;

  HexPanes(Workers workers, UiDispatcher ui, MainView view, HexViewers viewers, int bytesPerRow) {
    this.workers = workers;
    this.ui = ui;
    this.view = view;
    this.viewers = viewers;
    this.bytesPerRow = bytesPerRow;
  }

  /** Returns a tab's hex state, creating it in Text. */
  HexTab of(OpenDocument d) {
    if (d.hex == null) {
      d.hex = new HexTab(d, workers, ui, view, viewers, bytesPerRow);
    }
    return d.hex;
  }

  /**
   * Returns a tab's mode.
   *
   * @param d the tab, or null
   * @return Text without a tab or before the pane was used
   */
  static HexMode mode(OpenDocument d) {
    return d == null || d.hex == null ? HexMode.TEXT : d.hex.mode();
  }

  /**
   * Switches a tab's mode as the user's pick, which the tab then keeps.
   *
   * @param d the tab
   * @param mode the mode
   * @return true when it changed
   */
  boolean pick(OpenDocument d, HexMode mode) {
    HexTab tab = of(d);
    tab.setPicked(true);
    return apply(d, mode);
  }

  private boolean apply(OpenDocument d, HexMode mode) {
    if (mode != HexMode.TEXT
        && d.markdown != null
        && d.markdown.mode() != MarkdownViewMode.SOURCE) {
      d.markdown.setMode(MarkdownViewMode.SOURCE);
    }
    return of(d).setMode(mode);
  }

  /** Returns a tab to Text after its Markdown preview showed. */
  void markdownShown(OpenDocument d) {
    if (d.hex != null) {
      d.hex.setMode(HexMode.TEXT);
    }
  }

  /** Follows an editor of a tab whose pane exists. */
  void connect(OpenDocument d, EditorController editor) {
    if (d.hex != null) {
      d.hex.follow(editor);
    }
  }

  /** Builds a tab's offsets again after its encoding changed. */
  static void encodingChanged(OpenDocument d) {
    if (d.hex != null) {
      d.hex.encodingChanged();
    }
  }

  /**
   * Returns the controller of a tab whose pane shows and holds the focus.
   *
   * @param d the tab, or null
   * @return the controller, or null
   */
  static HexController focused(OpenDocument d) {
    return d != null && d.hex != null && d.hex.focused() ? d.hex.controller() : null;
  }

  /**
   * Returns the controller of a tab whose pane shows.
   *
   * @param d the tab, or null
   * @return the controller, or null in Text
   */
  static HexController shown(OpenDocument d) {
    if (d == null || d.hex == null || d.hex.mode() == HexMode.TEXT) {
      return null;
    }
    return d.hex.controller();
  }

  /** Sets every pane's bytes per row ({@code hex.bytesPerRow}). */
  void setBytesPerRow(Iterable<OpenDocument> documents, int value) {
    bytesPerRow = value;
    for (OpenDocument d : documents) {
      if (d.hex != null) {
        d.hex.setBytesPerRow(value);
      }
    }
  }

  /** Stops a closing or moving tab's pane. */
  void closed(OpenDocument d) {
    if (d.hex != null) {
      d.hex.dispose();
    }
  }

  /** Creates a tab's pane again in its new view after a window replacement. */
  void rebind(OpenDocument d) {
    if (d.hex != null) {
      d.hex.rebind();
    }
  }

  /** Gives a tab moved in from another window the mode it had there. */
  void adopt(OpenDocument d, OpenDocument from) {
    if (from.hex != null) {
      HexTab tab = of(d);
      tab.setPicked(from.hex.picked());
      apply(d, from.hex.mode());
    }
  }

  /**
   * Applies a tab's saved pick, which the tab keeps as its pick.
   *
   * @param d the tab
   * @param saved the mode as {@link #saved} wrote it; empty does nothing
   */
  void restore(OpenDocument d, String saved) {
    if (saved.isEmpty()) {
      return;
    }
    of(d).setPicked(true);
    apply(d, parse(saved));
  }

  /**
   * Returns a tab's mode as the session and the file memory keep it: the user's pick only.
   *
   * @param d the tab
   * @return {@code text}, {@code split} or {@code hex}; empty while the user picked none
   */
  static String saved(OpenDocument d) {
    if (d.hex == null || !d.hex.picked()) {
      return "";
    }
    return switch (d.hex.mode()) {
      case TEXT -> TEXT;
      case SPLIT -> SPLIT;
      case HEX -> HEX;
    };
  }

  /** Parses a saved mode; anything else is Text. */
  static HexMode parse(String saved) {
    return switch (saved) {
      case SPLIT -> HexMode.SPLIT;
      case HEX -> HexMode.HEX;
      default -> HexMode.TEXT;
    };
  }
}
