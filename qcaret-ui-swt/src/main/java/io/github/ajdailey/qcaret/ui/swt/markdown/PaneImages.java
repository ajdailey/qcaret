package io.github.ajdailey.qcaret.ui.swt.markdown;

import io.github.ajdailey.qcaret.app.view.DecodedImage;
import io.github.ajdailey.qcaret.app.view.MarkdownImageRequest;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.eclipse.swt.graphics.Device;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;

/**
 * The local images of the content a pane shows, by the rendered offset of each one's placeholder
 * char: the request, then the {@link Image} once decoded or a failure mark. {@link #reset} and
 * {@link #dispose} dispose every image; a result for a request of earlier content is dropped. Call
 * on the UI thread.
 */
final class PaneImages {
  /** The space an image of unknown size reserves until it decodes. */
  static final int PENDING = 32;

  /** The size of a placeholder for an image that does not decode. */
  static final Point FAILED = new Point(160, 40);

  private final Device device;
  private final Map<Integer, MarkdownImageRequest> requests =
      new HashMap<Integer, MarkdownImageRequest>();
  private final Map<Integer, Image> images = new HashMap<Integer, Image>();
  private final Set<Integer> failed = new HashSet<Integer>();
  private int[] offsets = new int[0];
  private int created;
  private int disposed;

  PaneImages(Device device) {
    this.device = device;
  }

  /** Disposes every image and takes the requests of new content. */
  void reset(List<MarkdownImageRequest> next) {
    dispose();
    offsets = new int[next.size()];
    for (int i = 0; i < offsets.length; i++) {
      MarkdownImageRequest request = next.get(i);
      offsets[i] = request.offset();
      requests.put(request.offset(), request);
    }
  }

  /**
   * Takes a decoded image.
   *
   * @param request the request it answers
   * @param image the image, or null when it did not decode
   * @return false, keeping nothing, for a request of earlier content
   */
  boolean accept(MarkdownImageRequest request, DecodedImage image) {
    if (requests.get(request.offset()) != request) {
      return false;
    }
    Image old = images.remove(request.offset());
    if (old != null) {
      old.dispose();
      disposed++;
    }
    if (image instanceof SwtDecodedImage decoded) {
      images.put(request.offset(), new Image(device, decoded.data()));
      created++;
      failed.remove(request.offset());
    } else {
      failed.add(request.offset());
    }
    return true;
  }

  /** Returns the offsets of the images, ascending. */
  int[] offsets() {
    return offsets;
  }

  /** Returns an image's request, or null for an offset holding none. */
  MarkdownImageRequest request(int offset) {
    return requests.get(offset);
  }

  /** Returns a decoded image, or null. */
  Image image(int offset) {
    return images.get(offset);
  }

  /** Reports whether an image failed to decode. */
  boolean failed(int offset) {
    return failed.contains(offset);
  }

  /**
   * Returns the size an image takes: decoded, failed, or reserved from its request.
   *
   * @param offset the image's offset
   * @param paneWidth the pane's text width, for a percent width
   * @return the size in pixels
   */
  Point size(int offset, int paneWidth) {
    Image image = images.get(offset);
    if (image != null) {
      return new Point(image.getBounds().width, image.getBounds().height);
    }
    if (failed.contains(offset)) {
      return FAILED;
    }
    MarkdownImageRequest r = requests.get(offset);
    int width = r.width() > 0 ? r.width() : r.percent() > 0 ? paneWidth * r.percent() / 100 : 0;
    width = Math.min(width, Math.max(1, paneWidth));
    int height = r.height() > 0 ? r.height() : width > 0 ? width * 3 / 4 : PENDING;
    return new Point(width > 0 ? width : PENDING, height);
  }

  /** Counts the images created and not yet disposed. */
  int live() {
    return created - disposed;
  }

  /** Disposes every image and forgets the requests. */
  void dispose() {
    for (Image image : images.values()) {
      image.dispose();
      disposed++;
    }
    images.clear();
    failed.clear();
    requests.clear();
    offsets = new int[0];
  }
}
