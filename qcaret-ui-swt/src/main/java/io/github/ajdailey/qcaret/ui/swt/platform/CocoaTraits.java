package io.github.ajdailey.qcaret.ui.swt.platform;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.widgets.Control;

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
  public boolean typesWithAlt() {
    return true;
  }

  @Override
  public boolean systemAppMenu() {
    return true;
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
    return false;
  }

  @Override
  public boolean explainsUnsupportedStacking() {
    return false;
  }

  @Override
  public boolean menuImages() {
    return false;
  }

  @Override
  public boolean padsMenuImages() {
    return false;
  }

  @Override
  public boolean fixedMenuInk() {
    return false;
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
    return true;
  }

  @Override
  public boolean themesAtCreation() {
    return false;
  }

  @Override
  public boolean raisesLinkContrast() {
    return false;
  }

  @Override
  public boolean lightChecksUnderDark() {
    return false;
  }

  @Override
  public boolean colorsDialogs() {
    return false;
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
  public boolean opensPrintDialog() {
    return true;
  }

  @Override
  public int busyCursor() {
    return SWT.CURSOR_WAIT;
  }

  @Override
  public boolean handsFilesAsEvents() {
    return true;
  }

  @Override
  public boolean pollsSystemTheme() {
    return true;
  }

  @Override
  public boolean windowMenuControls() {
    return true;
  }

  @Override
  public boolean labelsByDescription() {
    return true;
  }

  @Override
  public boolean printDialogOmitsSelection() {
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
}
