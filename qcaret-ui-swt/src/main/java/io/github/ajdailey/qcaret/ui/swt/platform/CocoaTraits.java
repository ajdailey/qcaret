package io.github.ajdailey.qcaret.ui.swt.platform;

import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;

/** cocoa. */
final class CocoaTraits implements PlatformTraits {
  @Override
  public String name() {
    return "cocoa";
  }

  @Override
  public String monospaceFamily() {
    return "Menlo";
  }

  @Override
  public int monospaceSize() {
    return 12;
  }

  @Override
  public CaretBlend caretBlend() {
    return CaretBlend.DIFFERENCE;
  }

  @Override
  public String toolkitVersion() {
    return null;
  }

  @Override
  public Color shownBackground(Control source) {
    return source.getBackground();
  }

  @Override
  public boolean macKeys() {
    return true;
  }

  @Override
  public boolean linuxKeys() {
    return false;
  }

  @Override
  public int busyCursor() {
    return SWT.CURSOR_WAIT;
  }

  @Override
  public ChromeColors chromeColors(Display display) {
    return new NativeChromeColors();
  }

  @Override
  public DialogColors dialogColors() {
    return NativeDialogColors.INSTANCE;
  }

  @Override
  public ThemeFollow themeFollow(Display display) {
    return new CocoaThemeFollow(display);
  }

  @Override
  public MenuBehavior menuBehavior() {
    return new CocoaMenuBehavior();
  }

  @Override
  public ToolItemSizing toolItemSizing() {
    return new NativeToolItemSizing();
  }

  @Override
  public KeyBehavior keyBehavior() {
    return new CocoaKeyBehavior();
  }

  @Override
  public BrowserBehavior browserBehavior() {
    return new CocoaBrowserBehavior();
  }

  @Override
  public PrintSupport printSupport() {
    return new CocoaPrintSupport();
  }

  @Override
  public FullScreen fullScreen(Shell shell, UiDispatcher ui) {
    return new CocoaFullScreen(shell, ui);
  }

  @Override
  public WindowStacking stacking() {
    return new CocoaStacking();
  }

  @Override
  public WindowPlacement windowPlacement() {
    return new NativeWindowPlacement();
  }

  @Override
  public NativeNames nativeNames() {
    return new NoNativeNames();
  }

  @Override
  public AccessibleLabels accessibleLabels() {
    return new CocoaAccessibleLabels();
  }

  @Override
  public LaunchDocuments launchDocuments() {
    return new CocoaLaunchDocuments();
  }

  @Override
  public GuideDisplay guideDisplay() {
    return new BrowserGuideDisplay();
  }
}
