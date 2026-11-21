package io.github.ajdailey.qcaret.ui.swt.platform;

/** GTK 4, which SWT loads under {@code SWT_GTK4=1} when GTK 4 is installed. */
final class Gtk4Traits extends GtkTraits {
  @Override
  public CaretBlend caretBlend() {
    return CaretBlend.OVER;
  }

  @Override
  public boolean opensBrowser() {
    return false;
  }

  @Override
  public boolean positionsWindows() {
    return false;
  }

  @Override
  public boolean sizesToolPictures() {
    return true;
  }

  @Override
  public boolean opensPrintDialog() {
    return false;
  }

  @Override
  public boolean stacksOnX11() {
    return false;
  }

  @Override
  public boolean showsMenuToolTips() {
    return false;
  }

  @Override
  public boolean acceleratorsFollowFocusControl() {
    return true;
  }

  @Override
  public boolean namesThroughAtk() {
    return false;
  }
}
