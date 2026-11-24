package io.github.ajdailey.qcaret.ui.swt.platform;

/** GTK 3, on X11 or Wayland. */
final class Gtk3Traits extends GtkTraits {
  @Override
  public CaretBlend caretBlend() {
    return CaretBlend.DIFFERENCE;
  }

  @Override
  public MenuBehavior menuBehavior() {
    return new Gtk3MenuBehavior();
  }

  @Override
  public ToolItemSizing toolItemSizing() {
    return new NativeToolItemSizing();
  }

  @Override
  public KeyBehavior keyBehavior() {
    return new Gtk3KeyBehavior();
  }

  @Override
  public BrowserBehavior browserBehavior() {
    return new Gtk3BrowserBehavior();
  }

  @Override
  public PrintSupport printSupport() {
    return new Gtk3PrintSupport();
  }

  /** Returns {@link GtkX11Stacking} when the GDK backend is {@code x11}. */
  @Override
  public WindowStacking stacking() {
    return "x11".equals(System.getProperty(GDK_BACKEND))
        ? new GtkX11Stacking()
        : new WindowManagerStacking();
  }

  @Override
  public NativeNames nativeNames() {
    return new Gtk3NativeNames();
  }

  @Override
  public AccessibleLabels accessibleLabels() {
    return new NativeAccessibleLabels();
  }

  @Override
  public WindowPlacement windowPlacement() {
    return new GtkWindowPlacement(false);
  }

  @Override
  public GuideDisplay guideDisplay() {
    return new BrowserGuideDisplay();
  }
}
