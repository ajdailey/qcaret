package io.github.ajdailey.qcaret.ui.swt.platform;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;

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
  public boolean typesWithAlt() {
    return false;
  }

  @Override
  public boolean systemAppMenu() {
    return false;
  }

  @Override
  public boolean checksPrinterList() {
    return true;
  }

  @Override
  public boolean opensBrowser() {
    return true;
  }

  @Override
  public boolean positionsWindows() {
    return true;
  }

  @Override
  public boolean remeasuresTrim() {
    return true;
  }

  @Override
  public boolean explainsUnsupportedStacking() {
    return false;
  }

  @Override
  public boolean menuImages() {
    return true;
  }

  @Override
  public boolean padsMenuImages() {
    return false;
  }

  @Override
  public boolean fixedMenuInk() {
    return true;
  }

  @Override
  public Color shownBackground(Control source) {
    return source.getBackground();
  }

  @Override
  public boolean fadesDisabledIcons() {
    return false;
  }

  @Override
  public boolean sizesToolPictures() {
    return false;
  }

  @Override
  public boolean switchesThemeLive() {
    return false;
  }

  @Override
  public boolean followsSystemAppearance() {
    return false;
  }

  @Override
  public boolean themesAtCreation() {
    return true;
  }

  @Override
  public boolean raisesLinkContrast() {
    return false;
  }

  @Override
  public ChromeColors chromeColors(Display display) {
    return new Win32ChromeColors(display);
  }

  @Override
  public DialogSurfaces dialogSurfaces() {
    return Win32DialogSurfaces.INSTANCE;
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
  public boolean opensPrintDialog() {
    return true;
  }

  @Override
  public int busyCursor() {
    return SWT.CURSOR_APPSTARTING;
  }

  @Override
  public boolean handsFilesAsEvents() {
    return false;
  }

  @Override
  public boolean pollsSystemTheme() {
    return true;
  }

  @Override
  public boolean windowMenuControls() {
    return false;
  }

  @Override
  public boolean labelsByDescription() {
    return false;
  }

  @Override
  public boolean printDialogOmitsSelection() {
    return true;
  }

  @Override
  public boolean fullScreenSpace() {
    return false;
  }

  @Override
  public boolean stacksOnX11() {
    return false;
  }

  @Override
  public String toolkitVersion() {
    return null;
  }

  @Override
  public boolean showsMenuToolTips() {
    return true;
  }

  @Override
  public boolean scrollsBrowserToFragments() {
    return false;
  }

  @Override
  public boolean acceleratorsFollowFocusControl() {
    return false;
  }

  @Override
  public boolean blankLinksReplacePage() {
    return false;
  }

  @Override
  public boolean namesThroughAtk() {
    return false;
  }

  @Override
  public boolean followsLinksWithoutGesture() {
    return true;
  }

  @Override
  public boolean browserKeysSkipAccelerators() {
    return true;
  }
}
