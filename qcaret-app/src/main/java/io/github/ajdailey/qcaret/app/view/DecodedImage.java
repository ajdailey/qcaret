package io.github.ajdailey.qcaret.app.view;

/** An image a {@link MarkdownImageDecoder} decoded and scaled, held as the toolkit's pixel data. */
public interface DecodedImage {
  /**
   * Returns the width it shows at.
   *
   * @return pixels
   */
  int width();

  /**
   * Returns the height it shows at.
   *
   * @return pixels
   */
  int height();

  /**
   * Estimates the heap it holds.
   *
   * @return bytes
   */
  long bytes();
}
