package io.github.ajdailey.qcaret.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.PaletteData;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** {@link Pixels} keeps one device pixel per logical pixel and leaves a 100 % read unchanged. */
class PixelsTest {
  private static final PaletteData DIRECT = new PaletteData(0xff0000, 0xff00, 0xff);
  private static final RGB GRAY = new RGB(0x80, 0x80, 0x80);
  private static final RGB RED = new RGB(0xff, 0, 0);
  private static final RGB BLUE = new RGB(0, 0, 0xff);
  private static final RGB GREEN = new RGB(0, 0xff, 0);

  /** A 2x image whose blocks start with a pure color and blend after it. */
  @Test
  void aDoubleZoomImageSamplesTheFirstPixelOfEachBlock() {
    ImageData device = new ImageData(6, 4, 24, DIRECT);
    for (int y = 0; y < device.height; y++) {
      for (int x = 0; x < device.width; x++) {
        device.setPixel(x, y, DIRECT.getPixel(GRAY));
      }
    }
    device.setPixel(0, 0, DIRECT.getPixel(RED));
    device.setPixel(2, 0, DIRECT.getPixel(BLUE));
    device.setPixel(4, 2, DIRECT.getPixel(GREEN));

    ImageData logical = Pixels.logical(device, 3, 2);

    assertEquals(3, logical.width);
    assertEquals(2, logical.height);
    assertEquals(RED, rgb(logical, 0, 0));
    assertEquals(BLUE, rgb(logical, 1, 0));
    assertEquals(GRAY, rgb(logical, 2, 0));
    assertEquals(GRAY, rgb(logical, 0, 1));
    assertEquals(GREEN, rgb(logical, 2, 1));
  }

  @Test
  void anImageAtItsLogicalSizeIsReturnedAsIs() {
    ImageData device = new ImageData(3, 2, 24, DIRECT);
    assertSame(device, Pixels.logical(device, 3, 2));
  }

  @Test
  @Tag("swt")
  void aReadAtOneHundredPercentMatchesGetImageData() {
    Display display = new Display();
    try {
      Shell shell = new Shell(display);
      assumeTrue(shell.getMonitor().getZoom() == 100, "monitor zoom is not 100 %");
      Image image = new Image(display, 8, 6);
      try {
        GC gc = new GC(image);
        try {
          gc.setBackground(new Color(RED));
          gc.fillRectangle(0, 0, 8, 6);
          gc.setBackground(new Color(BLUE));
          gc.fillRectangle(3, 2, 3, 2);
        } finally {
          gc.dispose();
        }
        ImageData expected = image.getImageData();
        ImageData read = Pixels.read(image, shell);
        assertEquals(expected.width, read.width);
        assertEquals(expected.height, read.height);
        for (int y = 0; y < expected.height; y++) {
          for (int x = 0; x < expected.width; x++) {
            assertEquals(rgb(expected, x, y), rgb(read, x, y), "at " + x + "," + y);
          }
        }
        assertEquals(BLUE, rgb(read, 4, 3));
      } finally {
        image.dispose();
      }
    } finally {
      display.dispose();
    }
  }

  private static RGB rgb(ImageData data, int x, int y) {
    return data.palette.getRGB(data.getPixel(x, y));
  }
}
