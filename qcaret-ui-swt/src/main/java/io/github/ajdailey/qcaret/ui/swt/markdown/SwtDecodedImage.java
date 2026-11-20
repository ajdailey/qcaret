package io.github.ajdailey.qcaret.ui.swt.markdown;

import io.github.ajdailey.qcaret.app.view.DecodedImage;
import org.eclipse.swt.graphics.ImageData;

/** Decoded, scaled pixels; the UI thread creates the {@code Image} from them. */
final class SwtDecodedImage implements DecodedImage {
  private final ImageData data;

  SwtDecodedImage(ImageData data) {
    this.data = data;
  }

  /** Returns the pixels. */
  ImageData data() {
    return data;
  }

  @Override
  public int width() {
    return data.width;
  }

  @Override
  public int height() {
    return data.height;
  }

  @Override
  public long bytes() {
    long pixels = (long) data.bytesPerLine * data.height;
    return pixels + (data.alphaData == null ? 0 : data.alphaData.length);
  }
}
