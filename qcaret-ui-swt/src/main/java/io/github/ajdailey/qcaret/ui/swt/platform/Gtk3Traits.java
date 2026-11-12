package io.github.ajdailey.qcaret.ui.swt.platform;

/** GTK 3, on X11 or Wayland. */
final class Gtk3Traits extends GtkTraits {
  @Override
  public CaretBlend caretBlend() {
    return CaretBlend.DIFFERENCE;
  }

  @Override
  public boolean opensBrowser() {
    return true;
  }

  @Override
  public boolean positionsWindows() {
    return !"wayland".equals(System.getProperty(GDK_BACKEND));
  }

  @Override
  public boolean sizesToolPictures() {
    return false;
  }
}
