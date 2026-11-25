package io.github.ajdailey.qcaret.ui.swt;

import org.eclipse.swt.printing.PrintDialog;
import org.eclipse.swt.printing.PrinterData;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.DirectoryDialog;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Shell;

/**
 * Opens the platform's file, folder and print dialogs and counts each one on its top-level shell
 * while its nested event loop runs, so {@link WindowViews} replaces that window only after it
 * returns. Every native dialog opens through here. Confined to the UI thread.
 */
final class NativeDialogs {
  /** Shell data key: the number of native dialogs open over the shell. */
  private static final String OPEN = NativeDialogs.class.getName() + ".open";

  private NativeDialogs() {}

  static String open(FileDialog dialog) {
    Shell top = enter(dialog.getParent());
    try {
      return dialog.open();
    } finally {
      leave(top);
    }
  }

  static String open(DirectoryDialog dialog) {
    Shell top = enter(dialog.getParent());
    try {
      return dialog.open();
    } finally {
      leave(top);
    }
  }

  static PrinterData open(PrintDialog dialog) {
    Shell top = enter(dialog.getParent());
    try {
      return dialog.open();
    } finally {
      leave(top);
    }
  }

  /**
   * Reports whether a native dialog runs over a top-level shell or any shell it owns.
   *
   * @param shell a top-level shell
   * @return true while one is open
   */
  static boolean openOver(Shell shell) {
    return count(shell) > 0;
  }

  /**
   * Counts a dialog on the top-level shell that owns {@code parent}.
   *
   * @param parent the dialog's parent
   * @return the top-level shell, for {@link #leave}
   */
  static Shell enter(Shell parent) {
    Shell top = top(parent);
    top.setData(OPEN, count(top) + 1);
    return top;
  }

  /**
   * Ends the count {@link #enter} made.
   *
   * @param top the shell {@link #enter} returned
   */
  static void leave(Shell top) {
    if (!top.isDisposed()) {
      top.setData(OPEN, Math.max(0, count(top) - 1));
    }
  }

  /**
   * Returns the top-level shell that owns a shell.
   *
   * @param shell a shell
   * @return the shell itself when it has no owner
   */
  static Shell top(Shell shell) {
    Shell top = shell;
    Composite parent = top.getParent();
    while (parent instanceof Shell owner) {
      top = owner;
      parent = top.getParent();
    }
    return top;
  }

  private static int count(Shell shell) {
    return shell.getData(OPEN) instanceof Integer n ? n : 0;
  }
}
