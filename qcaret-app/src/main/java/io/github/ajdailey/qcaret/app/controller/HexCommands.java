package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.view.HexCopy;
import io.github.ajdailey.qcaret.app.view.HexMode;
import io.github.ajdailey.qcaret.app.view.MainView;

/**
 * View > Hex View, Cycle Hex View and File > Open Bytes in Hex Viewer for one window, and the
 * editor commands a focused hex pane takes over: Copy, Select All, Go to Line (Go to Offset), Find,
 * Find Next, Find Previous and Esc. The mode belongs to the selected tab. Confined to the UI
 * thread.
 */
final class HexCommands {
  private final DocumentController documents;
  private final MainView view;

  HexCommands(DocumentController documents, MainView view) {
    this.documents = documents;
    this.view = view;
  }

  /** Runs a Hex View command or Open in Hex Viewer. */
  void execute(CommandId command) {
    switch (command) {
      case HEX_TEXT -> setMode(HexMode.TEXT);
      case HEX_SPLIT -> setMode(HexMode.SPLIT);
      case HEX_HEX -> setMode(HexMode.HEX);
      case HEX_CYCLE -> setMode(next(documents.hexMode()));
      case OPEN_IN_HEX_VIEWER -> documents.openInHexViewer();
      default -> throw new IllegalArgumentException(command.name());
    }
  }

  /**
   * Runs a command on the selected tab's hex pane while it shows and holds the focus.
   *
   * @param command any command
   * @return true when the pane took the command; false leaves it to the editor
   */
  boolean runFocused(CommandId command) {
    HexController hex = documents.hexFocused();
    if (hex == null) {
      return false;
    }
    switch (command) {
      case COPY -> hex.copy(HexCopy.FOCUSED);
      case SELECT_ALL -> hex.selectAll();
      case GO_TO_LINE -> hex.goToOffset();
      case FIND, REPLACE -> hex.openFind();
      case FIND_NEXT -> hex.findAgain(true);
      case FIND_PREVIOUS -> hex.findAgain(false);
      case CANCEL -> hex.cancel();
      default -> {
        return false;
      }
    }
    return true;
  }

  private void setMode(HexMode mode) {
    if (documents.setHexMode(mode)) {
      view.documentStateChanged();
    }
  }

  private static HexMode next(HexMode mode) {
    return switch (mode) {
      case TEXT -> HexMode.SPLIT;
      case SPLIT -> HexMode.HEX;
      case HEX -> HexMode.TEXT;
    };
  }

  /** {@link CommandStates#checked} for a Hex View mode. */
  boolean checked(CommandId command) {
    return switch (command) {
      case HEX_TEXT -> documents.hexMode() == HexMode.TEXT;
      case HEX_SPLIT -> documents.hexMode() == HexMode.SPLIT;
      case HEX_HEX -> documents.hexMode() == HexMode.HEX;
      default -> false;
    };
  }

  /**
   * Reports whether Copy has a hex selection to copy.
   *
   * @return true while the focused pane has bytes selected
   */
  boolean copyEnabled() {
    HexController hex = documents.hexFocused();
    return hex != null && hex.selection().length() > 0;
  }
}
