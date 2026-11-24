package io.github.ajdailey.qcaret.ui.swt.platform;

/** GTK 4, which SWT loads under {@code SWT_GTK4=1} when GTK 4 is installed. */
final class Gtk4Traits extends GtkTraits {
  @Override
  public CaretBlend caretBlend() {
    return CaretBlend.OVER;
  }

  @Override
  public MenuBehavior menuBehavior() {
    return new Gtk4MenuBehavior();
  }

  @Override
  public ToolItemSizing toolItemSizing() {
    return new Gtk4ToolItemSizing();
  }

  @Override
  public KeyBehavior keyBehavior() {
    return new Gtk4KeyBehavior();
  }

  @Override
  public BrowserBehavior browserBehavior() {
    return new Gtk4BrowserBehavior();
  }

  @Override
  public PrintSupport printSupport() {
    return new Gtk4PrintSupport();
  }

  @Override
  public WindowStacking stacking() {
    return new WindowManagerStacking();
  }

  @Override
  public NativeNames nativeNames() {
    return new NoNativeNames();
  }

  @Override
  public AccessibleLabels accessibleLabels() {
    return new NativeAccessibleLabels();
  }

  @Override
  public WindowPlacement windowPlacement() {
    return new GtkWindowPlacement(true);
  }

  @Override
  public GuideDisplay guideDisplay() {
    return new NoticeGuideDisplay();
  }
}
