package io.github.ajdailey.qcaret.ui.swt;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.layout.RowLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;

/**
 * Shared pieces of QCaret's dialogs. Every dialog opens without a nested event loop: {@code open}
 * returns at once and buttons act through listeners, so the UI thread never waits on the user.
 */
final class Dialogs {
  private Dialogs() {}

  /**
   * Creates a dialog shell owned by the main window.
   *
   * @param parent the main window
   * @param title the title
   * @param modal true to block input to the main window while it shows
   * @return the shell, laid out as one grid column
   */
  static Shell shell(Shell parent, String title, boolean modal) {
    return shell(parent, title, modal ? SWT.DIALOG_TRIM | SWT.APPLICATION_MODAL : SWT.SHELL_TRIM);
  }

  /**
   * Creates a dialog shell with a given style.
   *
   * @param parent the owning shell
   * @param title the title
   * @param style the {@link Shell} style
   * @return the shell, laid out as one grid column
   */
  static Shell shell(Shell parent, String title, int style) {
    Shell shell = new Shell(parent, style);
    shell.setText(title);
    GridLayout layout = new GridLayout(1, false);
    layout.marginWidth = 12;
    layout.marginHeight = 12;
    layout.verticalSpacing = 8;
    shell.setLayout(layout);
    return shell;
  }

  /**
   * Creates a right-aligned row for buttons.
   *
   * @param parent the dialog
   * @param span grid columns the row spans
   * @return the row
   */
  static Composite buttonRow(Composite parent, int span) {
    Composite row = new Composite(parent, SWT.NONE);
    RowLayout layout = new RowLayout(SWT.HORIZONTAL);
    layout.marginLeft = 0;
    layout.marginRight = 0;
    layout.spacing = 8;
    row.setLayout(layout);
    row.setLayoutData(new GridData(SWT.END, SWT.CENTER, true, false, span, 1));
    return row;
  }

  /**
   * Adds a push button.
   *
   * @param row the button row
   * @param text the label
   * @param action runs on the UI thread when pressed
   * @return the button
   */
  static Button button(Composite row, String text, final Runnable action) {
    Button button = new Button(row, SWT.PUSH);
    button.setText(text);
    button.addListener(
        SWT.Selection,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            action.run();
          }
        });
    return button;
  }

  /**
   * Adds a wrapping label.
   *
   * @param parent the parent
   * @param text the text
   * @return the label
   */
  static Label label(Composite parent, String text) {
    Label label = new Label(parent, SWT.WRAP);
    label.setText(text);
    label.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    return label;
  }

  /**
   * Returns an action that disposes a shell.
   *
   * @param shell the shell
   * @return the action
   */
  static Runnable closer(final Shell shell) {
    return new Runnable() {
      @Override
      public void run() {
        shell.dispose();
      }
    };
  }

  /**
   * Opens a dialog centered over its parent.
   *
   * @param shell the dialog
   * @param width preferred width, or {@code SWT.DEFAULT}
   * @param height preferred height, or {@code SWT.DEFAULT}
   */
  static void open(Shell shell, int width, int height) {
    shell.pack();
    Point size = shell.computeSize(width, height);
    Rectangle owner = shell.getParent().getBounds();
    shell.setBounds(
        owner.x + Math.max(0, (owner.width - size.x) / 2),
        owner.y + Math.max(0, (owner.height - size.y) / 2),
        size.x,
        size.y);
    shell.open();
  }

  /**
   * Returns the platform's monospace font family.
   *
   * @return Consolas on Windows, Menlo on macOS, Monospace on Linux
   */
  static String monospaceName() {
    return switch (SWT.getPlatform()) {
      case "win32" -> "Consolas";
      case "cocoa" -> "Menlo";
      default -> "Monospace";
    };
  }

  /**
   * Returns the default monospace size.
   *
   * @return points
   */
  static int monospaceSize() {
    return "cocoa".equals(SWT.getPlatform()) ? 12 : 10;
  }
}
