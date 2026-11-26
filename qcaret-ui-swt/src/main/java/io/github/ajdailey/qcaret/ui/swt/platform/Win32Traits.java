package io.github.ajdailey.qcaret.ui.swt.platform;

import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;

/** win32. */
final class Win32Traits implements PlatformTraits {
  @Override
  public String name() {
    return "win32";
  }

  @Override
  public String monospaceFamily() {
    return "Consolas";
  }

  @Override
  public int monospaceSize() {
    return 10;
  }

  @Override
  public CaretBlend caretBlend() {
    return CaretBlend.XOR;
  }

  @Override
  public String toolkitVersion() {
    return "";
  }

  @Override
  public Color shownBackground(Control source) {
    return source.getBackground();
  }

  @Override
  public boolean macKeys() {
    return false;
  }

  @Override
  public boolean linuxKeys() {
    return false;
  }

  @Override
  public int busyCursor() {
    return SWT.CURSOR_APPSTARTING;
  }

  @Override
  public int layoutZoom(Control control) {
    String scale = System.getProperty("swt.autoScale");
    if (scale != null) {
      try {
        return Integer.parseInt(scale.trim());
      } catch (NumberFormatException named) {
        return control.getMonitor().getZoom();
      }
    }
    return control.getMonitor().getZoom();
  }

  @Override
  public ChromeColors chromeColors(Display display) {
    return new Win32ChromeColors(display);
  }

  @Override
  public DialogColors dialogColors() {
    return Win32DialogColors.INSTANCE;
  }

  @Override
  public DialogTabs dialogTabs(Composite parent) {
    return DialogColors.nativeDark(parent.getDisplay())
        ? new Win32DialogTabs(parent)
        : new NativeDialogTabs(parent);
  }

  @Override
  public ThemeFollow themeFollow(Display display) {
    return new Win32ThemeFollow(display);
  }

  @Override
  public MenuBehavior menuBehavior() {
    return new Win32MenuBehavior();
  }

  @Override
  public ToolItemSizing toolItemSizing() {
    return new NativeToolItemSizing();
  }

  @Override
  public KeyBehavior keyBehavior() {
    return new Win32KeyBehavior();
  }

  @Override
  public BrowserBehavior browserBehavior() {
    return new Win32BrowserBehavior();
  }

  @Override
  public PrintSupport printSupport() {
    return new Win32PrintSupport();
  }

  @Override
  public FullScreen fullScreen(Shell shell, UiDispatcher ui) {
    return new NativeFullScreen(shell);
  }

  @Override
  public WindowStacking stacking() {
    return new Win32Stacking();
  }

  @Override
  public WindowPlacement windowPlacement() {
    return new Win32WindowPlacement();
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
  public LaunchDocuments launchDocuments() {
    return new NativeLaunchDocuments();
  }

  @Override
  public GuideDisplay guideDisplay() {
    return new BrowserGuideDisplay();
  }

  @Override
  public CaretPlacement caretPlacement() {
    return new NativeCaretPlacement();
  }
}
