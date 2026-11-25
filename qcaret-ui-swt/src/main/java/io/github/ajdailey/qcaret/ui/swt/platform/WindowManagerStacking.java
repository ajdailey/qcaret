package io.github.ajdailey.qcaret.ui.swt.platform;

import io.github.ajdailey.qcaret.app.i18n.UiText;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Shell;

/**
 * Always on Top on GTK 3 under Wayland and on GTK 4: unsupported, {@link #setOnTop} does nothing.
 */
final class WindowManagerStacking implements WindowStacking {
  @Override
  public boolean supported() {
    return false;
  }

  @Override
  public void setOnTop(Shell shell, boolean onTop) {}

  @Override
  public void explainUnsupported(MenuItem item) {
    item.setToolTipText(UiText.ALWAYS_ON_TOP_UNSUPPORTED.text());
  }

  @Override
  public void minimize(Shell shell) {
    shell.setMinimized(true);
  }
}
