package io.github.ajdailey.qcaret.ui.swt;

import io.github.ajdailey.qcaret.app.view.WindowGeometry;
import io.github.ajdailey.qcaret.ui.swt.platform.DialogColors;
import io.github.ajdailey.qcaret.ui.swt.platform.DialogTabs;
import io.github.ajdailey.qcaret.ui.swt.platform.MenuBehavior;
import io.github.ajdailey.qcaret.ui.swt.platform.PlatformTraits;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Image;
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
   * Creates a dialog shell with a given style, carrying its owner's icons and the platform's {@link
   * DialogColors} colors. A modeless shell is a tool window and takes its main window's menus where
   * the platform shows them ({@link MenuBehavior#toolWindowMenus}).
   *
   * @param parent the owning shell
   * @param title the title
   * @param style the {@link Shell} style
   * @return the shell, laid out as one grid column
   */
  static Shell shell(Shell parent, String title, int style) {
    Shell shell = new Shell(parent, style);
    shell.setText(title);
    inheritIcons(shell, parent);
    if ((style & (SWT.APPLICATION_MODAL | SWT.PRIMARY_MODAL | SWT.SYSTEM_MODAL)) == 0) {
      MenuBehavior.PLATFORM.toolWindowMenus(shell, ToolMenuBars.of(parent));
    }
    DialogColors.platform().apply(shell);
    GridLayout layout = new GridLayout(1, false);
    layout.marginWidth = 12;
    layout.marginHeight = 12;
    layout.verticalSpacing = 8;
    shell.setLayout(layout);
    return shell;
  }

  /**
   * Gives a shell its owner's images when the owner has any. A dialog opened from a dialog takes
   * them from the owning dialog. The images stay owned by the shell that loaded them: the caller
   * never disposes them.
   *
   * @param shell the new shell
   * @param owner the shell that owns it
   */
  static void inheritIcons(Shell shell, Shell owner) {
    Image[] images = owner.getImages();
    if (images.length > 0) {
      shell.setImages(images);
    }
  }

  /**
   * Returns the background of a dialog owned by a shell, which on win32 under the dark native
   * setting differs from the owner's.
   *
   * @param owner the owning shell
   * @return the color
   */
  static Color background(Shell owner) {
    return DialogColors.platform().background(owner);
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
   * Opens a dialog centered over its parent, inside the work area of the parent's monitor.
   *
   * @param shell the dialog
   * @param width preferred width, or {@code SWT.DEFAULT}
   * @param height preferred height, or {@code SWT.DEFAULT}
   */
  static void open(Shell shell, int width, int height) {
    open(shell, width, height, shell.getParent().getMonitor().getClientArea());
  }

  /**
   * Opens a dialog centered over its parent, inside a work area.
   *
   * @param shell the dialog
   * @param width preferred width, or {@code SWT.DEFAULT}
   * @param height preferred height, or {@code SWT.DEFAULT}
   * @param workArea the area the dialog stays inside
   */
  static void open(Shell shell, int width, int height, Rectangle workArea) {
    shell.pack();
    Point size = shell.computeSize(width, height);
    center(shell, size.x, size.y, workArea);
    shell.open();
  }

  /**
   * Sizes a dialog and centers it over its parent, inside the work area of the parent's monitor.
   *
   * @param shell the dialog
   * @param width outer width
   * @param height outer height
   */
  static void center(Shell shell, int width, int height) {
    center(shell, width, height, shell.getParent().getMonitor().getClientArea());
  }

  /**
   * Sizes a dialog and centers it over its parent, inside a work area ({@link WindowGeometry#fit}).
   *
   * @param shell the dialog
   * @param width outer width
   * @param height outer height
   * @param workArea the area the dialog stays inside
   */
  static void center(Shell shell, int width, int height, Rectangle workArea) {
    Rectangle owner = shell.getParent().getBounds();
    WindowGeometry.Rect fitted =
        WindowGeometry.fit(
            new WindowGeometry.Rect(
                owner.x + Math.max(0, (owner.width - width) / 2),
                owner.y + Math.max(0, (owner.height - height) / 2),
                width,
                height),
            new WindowGeometry.Rect(workArea.x, workArea.y, workArea.width, workArea.height));
    shell.setBounds(fitted.x(), fitted.y(), fitted.width(), fitted.height());
  }

  /**
   * Creates the platform's dialog tab folder ({@link PlatformTraits#dialogTabs}), filling a grid
   * cell.
   *
   * @param parent the dialog composite, laid out as a grid
   * @return the tabs
   */
  static DialogTabs tabs(Composite parent) {
    DialogTabs tabs = PlatformTraits.PLATFORM.dialogTabs(parent);
    tabs.control().setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
    return tabs;
  }

  /**
   * Returns the platform's monospace font family.
   *
   * @return Consolas on Windows, Menlo on macOS, Monospace on Linux
   */
  static String monospaceName() {
    return PlatformTraits.PLATFORM.monospaceFamily();
  }

  /**
   * Returns the default monospace size.
   *
   * @return points
   */
  static int monospaceSize() {
    return PlatformTraits.PLATFORM.monospaceSize();
  }
}
