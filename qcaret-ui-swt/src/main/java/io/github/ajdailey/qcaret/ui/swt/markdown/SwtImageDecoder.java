package io.github.ajdailey.qcaret.ui.swt.markdown;

import io.github.ajdailey.qcaret.app.view.DecodedImage;
import io.github.ajdailey.qcaret.app.view.MarkdownImageDecoder;
import io.github.ajdailey.qcaret.app.view.MarkdownImageRequest;
import java.io.ByteArrayInputStream;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.SWTException;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;

/**
 * Decodes PNG, JPEG, GIF, BMP and TIFF bytes with {@link ImageLoader}, first frame only, and scales
 * them to the request: a given width and height, one of them with the other from the aspect ratio,
 * or a share of the pane's width; then no wider than the pane. Touches no widget, so it runs on a
 * worker.
 */
final class SwtImageDecoder implements MarkdownImageDecoder {
  private final int maxWidth;
  private volatile String thread;

  /**
   * Creates a decoder for a pane's width.
   *
   * @param maxWidth the widest image, at least 1
   */
  SwtImageDecoder(int maxWidth) {
    this.maxWidth = Math.max(1, maxWidth);
  }

  @Override
  public int maxWidth() {
    return maxWidth;
  }

  @Override
  public DecodedImage decode(byte[] bytes, MarkdownImageRequest request, BooleanSupplier canceled) {
    ImageData[] frames;
    try {
      frames = new ImageLoader().load(new ByteArrayInputStream(bytes));
    } catch (SWTException e) {
      return null;
    }
    if (frames.length == 0 || canceled.getAsBoolean()) {
      return null;
    }
    thread = Thread.currentThread().getName();
    ImageData data = frames[0];
    int[] size = size(data.width, data.height, request, maxWidth);
    if (size[0] != data.width || size[1] != data.height) {
      data = data.scaledTo(size[0], size[1]);
    }
    return new SwtDecodedImage(data);
  }

  /**
   * Computes the size an image shows at.
   *
   * @param width the image's width
   * @param height the image's height
   * @param request the requested size
   * @param maxWidth the pane's width
   * @return width and height, each at least 1
   */
  static int[] size(int width, int height, MarkdownImageRequest request, int maxWidth) {
    long w = width;
    long h = height;
    if (request.width() > 0 && request.height() > 0) {
      w = request.width();
      h = request.height();
    } else if (request.width() > 0 || request.percent() > 0) {
      w = request.width() > 0 ? request.width() : (long) maxWidth * request.percent() / 100;
      h = height * w / Math.max(1, width);
    } else if (request.height() > 0) {
      h = request.height();
      w = width * h / Math.max(1, height);
    }
    if (w > maxWidth) {
      h = h * maxWidth / w;
      w = maxWidth;
    }
    return new int[] {(int) Math.max(1, w), (int) Math.max(1, h)};
  }

  /** Returns the name of the thread that decoded last, or null before a decode; for tests. */
  String thread() {
    return thread;
  }
}
