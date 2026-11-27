package io.github.ajdailey.qcaret.ui.swt;

import io.github.ajdailey.qcaret.app.command.Keymap;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.theme.Palette;
import io.github.ajdailey.qcaret.app.view.BinaryChoice;
import io.github.ajdailey.qcaret.app.view.HexInput;
import io.github.ajdailey.qcaret.app.view.HexMode;
import io.github.ajdailey.qcaret.app.view.HexView;
import io.github.ajdailey.qcaret.app.view.TabView;
import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import io.github.ajdailey.qcaret.ui.swt.hex.HexPane;
import io.github.ajdailey.qcaret.ui.swt.platform.ChromeColors;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;

/**
 * One main window's hex views: the tabs' hex panes ({@link HexSlot}), the Hex Viewer windows
 * ({@link HexWindow}) and the binary-file and too-large prompts that lead to them. Each pane and
 * window takes the window's palette, font and keys as they change; a pane's notice and find bars
 * follow the native theme with the window's other bars. Call on the UI thread.
 */
final class WindowHexViews {
  private final Shell shell;
  private final UiDispatcher timers;
  private final ChromeColors chrome;
  private final List<HexPane> panes = new ArrayList<HexPane>();
  private final List<HexWindow> windows = new ArrayList<HexWindow>();
  private Palette palette;
  private Font font;
  private Keymap keymap;

  /**
   * Creates the window's hex views; panes and windows open once {@link #setPalette}, {@link
   * #setFont} and {@link #setKeymap} have each run.
   */
  WindowHexViews(Shell shell, UiDispatcher timers, ChromeColors chrome) {
    this.shell = shell;
    this.timers = timers;
    this.chrome = chrome;
  }

  void askBinaryOpen(String name, Consumer<BinaryChoice> answer) {
    if (shell.isDisposed()) {
      answer.accept(BinaryChoice.CANCEL);
      return;
    }
    new BinaryDialog(shell, name, answer).open();
  }

  void showTooLarge(String message, Runnable openInHexViewer) {
    if (!shell.isDisposed()) {
      new ErrorDialog(
              shell,
              UiText.OPEN_TITLE.text(),
              message,
              UiText.BUTTON_OPEN_IN_HEX_VIEWER.text(),
              openInHexViewer)
          .open();
    }
  }

  HexView hexPane(TabView tab, HexInput input) {
    if (!(tab instanceof EditorTab editorTab) || editorTab.frame.isDisposed()) {
      return null;
    }
    final HexPane pane =
        new HexPane(
            editorTab.frame,
            input,
            palette,
            font,
            keymap,
            timers,
            new HexPane.Host() {
              @Override
              public void askGoToOffset(
                  Shell owner, Predicate<String> valid, Consumer<String> answer) {
                GoToDialog.offset(owner, valid, answer).open();
              }

              @Override
              public void close() {
                // A tab's pane closes with its tab.
              }
            },
            false);
    for (Control bar : pane.chrome()) {
      chrome.add(bar);
    }
    panes.add(pane);
    pane.control()
        .addListener(
            SWT.Dispose,
            new Listener() {
              @Override
              public void handleEvent(Event event) {
                panes.remove(pane);
              }
            });
    editorTab.hex = new HexSlot(editorTab.frame, editorTab.sash, pane, editorTab.focusEditor());
    return pane;
  }

  void showHexMode(TabView tab, HexMode mode) {
    if (tab instanceof EditorTab editorTab && editorTab.hex != null) {
      editorTab.hex.showMode(mode);
    }
  }

  HexView openHexViewer(String title, HexInput input) {
    if (shell.isDisposed()) {
      return null;
    }
    final HexWindow window = new HexWindow(shell, title, input, palette, font, keymap, timers);
    windows.add(window);
    window
        .shell()
        .addListener(
            SWT.Dispose,
            new Listener() {
              @Override
              public void handleEvent(Event event) {
                windows.remove(window);
              }
            });
    window.open();
    return window.pane();
  }

  /** Repaints every pane and window with a palette. */
  void setPalette(Palette next) {
    palette = next;
    for (HexPane pane : panes) {
      pane.setPalette(next);
    }
    for (HexWindow window : windows) {
      window.pane().setPalette(next);
    }
  }

  /** Paints every pane and window in another font; the caller disposes the old one after. */
  void setFont(Font next) {
    font = next;
    for (HexPane pane : panes) {
      pane.setFont(next);
    }
    for (HexWindow window : windows) {
      window.pane().setFont(next);
    }
  }

  /** Gives every pane and window changed key bindings. */
  void setKeymap(Keymap next) {
    keymap = next;
    for (HexPane pane : panes) {
      pane.setKeymap(next);
    }
    for (HexWindow window : windows) {
      window.pane().setKeymap(next);
    }
  }

  /** Returns the panes, oldest first. */
  List<HexPane> panes() {
    return panes;
  }

  /** Returns the open Hex Viewer windows, oldest first. */
  List<HexWindow> windows() {
    return windows;
  }
}
