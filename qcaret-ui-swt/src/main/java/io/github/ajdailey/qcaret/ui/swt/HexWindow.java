package io.github.ajdailey.qcaret.ui.swt;

import io.github.ajdailey.qcaret.app.command.Keymap;
import io.github.ajdailey.qcaret.app.theme.Palette;
import io.github.ajdailey.qcaret.app.view.HexInput;
import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import io.github.ajdailey.qcaret.ui.swt.hex.HexPane;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;

/**
 * A Hex Viewer window: a modeless shell owned by the main window holding one {@link HexPane}, which
 * runs the window keys itself (Find, Find Next and Previous, Go to Line, Close). It opens as wide
 * as a row and {@value #HEIGHT} px high, and closes with its owner. Activation focuses the rows.
 * Call on the UI thread.
 */
final class HexWindow {
  /** Initial height. */
  static final int HEIGHT = 560;

  private final Shell shell;
  private final HexPane pane;

  HexWindow(
      Shell parent,
      String title,
      HexInput input,
      Palette palette,
      Font font,
      Keymap keymap,
      UiDispatcher timers) {
    shell = Dialogs.shell(parent, title, SWT.SHELL_TRIM);
    GridLayout layout = new GridLayout(1, false);
    layout.marginWidth = 0;
    layout.marginHeight = 0;
    shell.setLayout(layout);
    pane =
        new HexPane(
            shell,
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
                shell.dispose();
              }
            },
            true);
    pane.control().setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
    shell.addListener(
        SWT.Activate,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            pane.focus();
          }
        });
  }

  /** Opens the window over its owner. */
  void open() {
    shell.layout(true, true);
    int width = shell.computeTrim(0, 0, pane.preferredWidth(), HEIGHT).width;
    Dialogs.center(shell, width, HEIGHT);
    shell.open();
    pane.focus();
  }

  Shell shell() {
    return shell;
  }

  HexPane pane() {
    return pane;
  }
}
