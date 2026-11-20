package io.github.ajdailey.qcaret.app.view;

import java.util.function.BooleanSupplier;

/**
 * Decodes local images for a Markdown preview pane. A pane creates one on the UI thread, holding
 * its width then; {@link #decode} runs on a worker and touches no widget.
 */
public interface MarkdownImageDecoder {
  /**
   * Returns the widest image it decodes.
   *
   * @return the pane's width in pixels when the decoder was created
   */
  int maxWidth();

  /**
   * Decodes an image file's bytes and scales the first frame to the request's size, no wider than
   * the pane.
   *
   * @param bytes the file's content
   * @param request the image and its size
   * @param canceled true once the pane drops the result
   * @return the image, or null when the bytes are no image the toolkit reads
   */
  DecodedImage decode(byte[] bytes, MarkdownImageRequest request, BooleanSupplier canceled);
}
