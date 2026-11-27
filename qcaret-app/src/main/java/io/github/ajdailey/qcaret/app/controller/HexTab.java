package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.view.ActivityIndicator;
import io.github.ajdailey.qcaret.app.view.ClipboardService;
import io.github.ajdailey.qcaret.app.view.HexMode;
import io.github.ajdailey.qcaret.app.view.HexSelection;
import io.github.ajdailey.qcaret.app.view.HexView;
import io.github.ajdailey.qcaret.app.view.MainView;
import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.document.Selection;
import io.github.ajdailey.qcaret.core.document.SelectionListener;
import io.github.ajdailey.qcaret.core.document.SelectionModel;
import io.github.ajdailey.qcaret.core.document.SelectionSet;
import io.github.ajdailey.qcaret.core.hex.ByteOffsets;
import io.github.ajdailey.qcaret.core.hex.EncodedHexSource;
import java.util.ArrayList;
import java.util.List;

/**
 * One editor tab's hex pane: its mode, its {@link HexController} over the document's encoded text
 * ({@link HexText}), and the caret sync with the tab's focused editor while the pane shows.
 *
 * <ul>
 *   <li>The focused editor's primary selection moves the hex caret and selects the bytes of the
 *       selected chars; other carets are ignored. A hex caret or selection moves the editor to the
 *       chars covering those bytes, a continuation byte or a BOM byte mapping to its char's start.
 *   <li>While the offsets are stale after an edit, a hex move waits and applies once they land;
 *       otherwise the landing offsets take the editor's selection.
 *   <li>A document whose bytes do not round-trip shows a notice with Open in Hex Viewer.
 * </ul>
 *
 * <p>Confined to the UI thread.
 */
final class HexTab implements HexController.Host, HexText.Target {
  private final OpenDocument d;
  private final MainView view;
  private final HexViewers viewers;
  private final HexController controller;
  private final HexText text;
  private final List<SelectionModel> followed = new ArrayList<SelectionModel>();
  private final SelectionListener editorMoved =
      new SelectionListener() {
        @Override
        public void selectionsChanged(SelectionModel model) {
          if (!syncing && mode != HexMode.TEXT && model == d.editor.selections()) {
            hexPending = null;
            fromEditor();
          }
        }
      };
  private HexMode mode = HexMode.TEXT;
  private HexView pane;
  private boolean picked;
  private boolean focused;
  private boolean syncing;
  private HexSelection hexPending;

  HexTab(
      OpenDocument d,
      Workers workers,
      UiDispatcher ui,
      MainView view,
      HexViewers viewers,
      int bytesPerRow) {
    this.d = d;
    this.view = view;
    this.viewers = viewers;
    this.controller = new HexController(workers, ui, this, false, bytesPerRow);
    this.text = new HexText(d.document, workers, ui, this);
    for (EditorController e : d.editors) {
      follow(e);
    }
  }

  HexMode mode() {
    return mode;
  }

  HexController controller() {
    return controller;
  }

  /** Reports whether the user picked the mode or a saved pick was restored. */
  boolean picked() {
    return picked;
  }

  void setPicked(boolean value) {
    picked = value;
  }

  /** Reports whether the pane shows and holds the keyboard focus. */
  boolean focused() {
    return focused && mode != HexMode.TEXT;
  }

  /** Follows an editor's selection; the focused one drives the sync. */
  void follow(EditorController editor) {
    SelectionModel model = editor.selections();
    if (!followed.contains(model)) {
      followed.add(model);
      model.addListener(editorMoved);
    }
  }

  /**
   * Switches the mode. Leaving Text creates the pane on first use and builds the offsets under the
   * busy cursor; Hex focuses the pane.
   *
   * @param next the mode
   * @return true when it changed; false when the view has no pane to show
   */
  boolean setMode(HexMode next) {
    if (next == mode) {
      return false;
    }
    if (next != HexMode.TEXT && pane == null) {
      pane = d.tab == null ? null : view.hexPane(d.tab, controller);
      if (pane == null) {
        return false;
      }
      controller.attach(pane);
    }
    mode = next;
    if (next == HexMode.TEXT) {
      text.stop();
      focused = false;
    } else {
      showGuard();
      if (text.start()) {
        fromEditor();
      }
    }
    if (d.tab != null) {
      view.showHexMode(d.tab, next);
    }
    if (next == HexMode.HEX && pane != null) {
      pane.focus();
    }
    return true;
  }

  /** Builds again in the document's new encoding. */
  void encodingChanged() {
    text.encodingChanged();
  }

  void setBytesPerRow(int bytesPerRow) {
    controller.setBytesPerRow(bytesPerRow);
  }

  /** Stops the pane's work and the sync. */
  void dispose() {
    text.stop();
    controller.dispose();
    for (SelectionModel model : followed) {
      model.removeListener(editorMoved);
    }
    followed.clear();
  }

  private void showGuard() {
    if (d.file.bytesGuarded) {
      controller.notice(
          UiText.HEX_BYTES_DIFFER.format(d.document.encoding().charset().displayName()),
          UiText.BUTTON_OPEN_IN_HEX_VIEWER.text());
    } else {
      controller.notice(null, null);
    }
  }

  /** Moves the hex caret and selection to the editor's primary selection. */
  private void fromEditor() {
    ByteOffsets offsets = text.current();
    if (offsets == null) {
      return;
    }
    Selection s = d.editor.selections().selections().primary();
    controller.select(offsets.byteOffset(s.anchor()), offsets.byteOffset(s.caret()));
  }

  /** Moves the editor's selection to the chars covering a hex selection. */
  private void toEditor(HexSelection selection, ByteOffsets offsets) {
    int anchor;
    int caret;
    if (selection.length() == 0) {
      anchor = offsets.charOffset(selection.caret());
      caret = anchor;
    } else {
      int start = offsets.charOffset(selection.start());
      int end = HexCopier.charEnd(offsets, selection.end());
      boolean forward = selection.caret() >= selection.anchor();
      anchor = forward ? start : end;
      caret = forward ? end : start;
    }
    syncing = true;
    try {
      d.editor.selections().set(SelectionSet.of(List.of(Selection.of(anchor, caret)), 0));
      d.editor.showCaret();
    } finally {
      syncing = false;
    }
  }

  @Override
  public void built(EncodedHexSource source) {
    controller.setSource(source);
    controller.setDimmed(false);
    HexSelection pending = hexPending;
    hexPending = null;
    if (pending != null) {
      controller.select(pending.anchor(), pending.caret());
      toEditor(pending, source.offsets());
    } else {
      fromEditor();
    }
  }

  @Override
  public void stale() {
    controller.setDimmed(true);
  }

  @Override
  public void unsupported() {
    view.showNotice(UiText.HEX_NO_ENCODER.format(d.document.encoding().charset().displayName()));
    setMode(HexMode.TEXT);
    view.documentStateChanged();
  }

  @Override
  public ActivityIndicator busy() {
    return d.editor.busyCursor();
  }

  @Override
  public ClipboardService clipboard() {
    return view;
  }

  @Override
  public void selectionChanged(HexSelection selection) {
    if (mode == HexMode.TEXT) {
      return;
    }
    ByteOffsets offsets = text.current();
    if (offsets == null) {
      hexPending = selection;
      return;
    }
    toEditor(selection, offsets);
  }

  @Override
  public void focusChanged(boolean value) {
    focused = value;
  }

  @Override
  public void sourceChanged() {
    // A document's encoded text does not change under a read.
  }

  @Override
  public void viewClosed() {
    pane = null;
  }

  @Override
  public void noticeAction() {
    if (d.file.path != null) {
      viewers.open(d.file.path);
    }
  }
}
