package io.github.ajdailey.qcaret.app.view;

import java.util.function.Consumer;

/** The window's hex panes, Hex Viewer windows and the prompts that lead to them. UI thread. */
public interface HexViews {
  /**
   * Asks how to open a binary file: Open in Hex Viewer (the default), Open as Text or Cancel.
   *
   * @param name the file's name
   * @param answer receives the choice once; closing the prompt answers {@link BinaryChoice#CANCEL}
   */
  void askBinaryOpen(String name, Consumer<BinaryChoice> answer);

  /**
   * Shows that a file is too large to open as text, with an Open in Hex Viewer button.
   *
   * @param message the refusal
   * @param openInHexViewer runs on the button
   */
  void showTooLarge(String message, Runnable openInHexViewer);

  /**
   * Creates a tab's hex pane in its side slot, hidden until {@link #showHexMode} shows it.
   *
   * @param tab the tab
   * @param input receives the pane's input
   * @return the pane
   */
  HexView hexPane(TabView tab, HexInput input);

  /**
   * Shows or hides a tab's editors and hex pane for a mode.
   *
   * @param tab a tab with a pane from {@link #hexPane}
   * @param mode the mode
   */
  void showHexMode(TabView tab, HexMode mode);

  /**
   * Opens a Hex Viewer window. Closing it calls {@link HexInput#attach} with null.
   *
   * @param title the file's name
   * @param input receives the window's input
   * @return the window's view
   */
  HexView openHexViewer(String title, HexInput input);
}
