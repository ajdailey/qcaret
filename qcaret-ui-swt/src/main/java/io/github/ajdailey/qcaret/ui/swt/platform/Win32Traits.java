package io.github.ajdailey.qcaret.ui.swt.platform;

import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.widgets.Control;

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
  public boolean launchesByExtension() {
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
  public boolean raisesLinkContrast() {
    return false;
  }

  @Override
  public boolean lightChecksUnderDark() {
    return true;
  }

  @Override
  public boolean colorsDialogs() {
    return true;
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
}
