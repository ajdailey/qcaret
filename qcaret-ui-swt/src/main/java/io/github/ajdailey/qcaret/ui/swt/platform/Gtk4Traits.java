package io.github.ajdailey.qcaret.ui.swt.platform;

/** GTK 4, the {@code SWT_GTK4=1} environment SWT reads. */
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
}
