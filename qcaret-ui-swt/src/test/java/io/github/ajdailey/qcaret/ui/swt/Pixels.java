package io.github.ajdailey.qcaret.ui.swt;

import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Control;

/**
 * Reads an image a test drew or captured into, in logical coordinates at any monitor zoom. The read
 * takes the image at the control's monitor zoom ({@code Image.getImageData(int)}), where the drawn
 * pixels are exact, and samples each logical pixel at the first device pixel of its block. On cocoa
 * {@code Image.getImageData()} redraws a 200 % image at 100 % with interpolation, which blends fill
 * edges and glyphs into colors no assertion expects.
 */
final class Pixels {
  private Pixels() {}

  /**
   * Returns an image's pixels, one per logical pixel.
   *
   * @param image the image, sized in logical pixels
   * @param on the control whose monitor sets the zoom
   * @return the pixels, as wide and high as {@link Image#getBounds}; at 100 % {@code
   *     image.getImageData()}
   */
  static ImageData read(Image image, Control on) {
    int zoom = on.getMonitor().getZoom();
    if (zoom == 100) {
      return image.getImageData();
    }
    Rectangle bounds = image.getBounds();
    return logical(image.getImageData(zoom), bounds.width, bounds.height);
  }

  /**
   * Samples device pixels down to a logical size, taking the first device pixel of each block.
   *
   * @param device the pixels at the monitor zoom
   * @param width the logical width
   * @param height the logical height
   * @return {@code device} when it already has the logical size, else a new image of that size
   */
  static ImageData logical(ImageData device, int width, int height) {
    if (device.width == width && device.height == height) {
      return device;
    }
    ImageData logical = new ImageData(width, height, device.depth, device.palette);
    for (int y = 0; y < height; y++) {
      int sourceY = (int) ((long) y * device.height / height);
      for (int x = 0; x < width; x++) {
        logical.setPixel(x, y, device.getPixel((int) ((long) x * device.width / width), sourceY));
      }
    }
    return logical;
  }
}
