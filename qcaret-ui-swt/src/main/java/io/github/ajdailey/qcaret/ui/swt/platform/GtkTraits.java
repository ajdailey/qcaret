package io.github.ajdailey.qcaret.ui.swt.platform;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Control;

/** What GTK 3 and GTK 4 share. */
abstract class GtkTraits implements PlatformTraits {
  /** Side of the image a visible control is drawn into; the corner holds no item. */
  private static final int SAMPLE = 2;

  @Override
  public String name() {
    return "gtk";
  }

  @Override
  public String monospaceFamily() {
    return "Monospace";
  }

  @Override
  public int monospaceSize() {
    return 10;
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
    return false;
  }

  @Override
  public boolean remeasuresTrim() {
    return false;
  }

  @Override
  public boolean launchesByExtension() {
    return false;
  }

  @Override
  public boolean explainsUnsupportedStacking() {
    return true;
  }

  @Override
  public boolean menuImages() {
    return true;
  }

  @Override
  public boolean padsMenuImages() {
    return true;
  }

  @Override
  public boolean fixedMenuInk() {
    return false;
  }

  @Override
  public Color shownBackground(Control source) {
    if (!source.isVisible()) {
      return source.getBackground();
    }
    Image image = new Image(source.getDisplay(), SAMPLE, SAMPLE);
    GC gc = new GC(image);
    try {
      if (!source.print(gc)) {
        return source.getBackground();
      }
      ImageData data = image.getImageData();
      RGB rgb = data.palette.getRGB(data.getPixel(SAMPLE - 1, SAMPLE - 1));
      return new Color(rgb);
    } finally {
      gc.dispose();
      image.dispose();
    }
  }

  @Override
  public boolean fadesDisabledIcons() {
    return true;
  }

  @Override
  public boolean switchesThemeLive() {
    return true;
  }

  @Override
  public boolean followsSystemAppearance() {
    return false;
  }

  @Override
  public boolean raisesLinkContrast() {
    return true;
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
    return false;
  }

  @Override
  public boolean linuxKeys() {
    return true;
  }

  @Override
  public int busyCursor() {
    return SWT.CURSOR_APPSTARTING;
  }
}
