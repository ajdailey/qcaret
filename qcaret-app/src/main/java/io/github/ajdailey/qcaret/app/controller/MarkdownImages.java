package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.view.DecodedImage;
import io.github.ajdailey.qcaret.app.view.MarkdownImageDecoder;
import io.github.ajdailey.qcaret.app.view.MarkdownImageRequest;
import io.github.ajdailey.qcaret.app.view.MarkdownPreviewView;
import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.WorkerThread;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.markdown.MarkdownImage;
import io.github.ajdailey.qcaret.core.markdown.RenderedMarkdown;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * The local images of one window's Markdown previews: the size each shows at, read and decoded on a
 * {@code qcaret-io-} worker, one image after another, each drawn as it arrives. A newer render, a
 * switch to Source or a closed tab cancels the running decode, and its remaining results are
 * dropped. An SVG, a file over {@value #MAX_FILE_BYTES} bytes and a file that cannot be read show
 * as placeholders. Decoded images stay cached, keyed by path, size, modification time, requested
 * size and pane width, up to {@value #CACHE_BYTES} bytes, least recently used first out, so a live
 * render after typing decodes nothing again. Confined to the UI thread but for the worker's cache
 * use.
 */
final class MarkdownImages {
  /** The largest image file decoded. */
  static final long MAX_FILE_BYTES = 16L * 1024 * 1024;

  /** The decoded pixels kept for later renders. */
  static final long CACHE_BYTES = 64L * 1024 * 1024;

  private final Workers workers;
  private final UiDispatcher ui;
  private final Map<String, DecodedImage> cache =
      new LinkedHashMap<String, DecodedImage>(16, 0.75f, true);
  private long cached;

  /**
   * Creates the images of one window.
   *
   * @param workers reads and decodes
   * @param ui receives results
   */
  MarkdownImages(Workers workers, UiDispatcher ui) {
    this.workers = workers;
    this.ui = ui;
  }

  /**
   * Lists a render's drawable images with the size each shows at.
   *
   * @param rendered the render
   * @return one request per {@link MarkdownImage#drawable} image, in rendered order
   */
  static List<MarkdownImageRequest> requests(RenderedMarkdown rendered) {
    List<MarkdownImageRequest> out = new ArrayList<MarkdownImageRequest>();
    for (MarkdownImage image : rendered.images()) {
      if (image.drawable()) {
        String width = image.width();
        int percent = percent(width);
        out.add(
            new MarkdownImageRequest(
                image.start(),
                image.target().path(),
                image.alt(),
                percent > 0 ? 0 : pixels(width),
                pixels(image.height()),
                percent));
      }
    }
    return out;
  }

  /** Parses {@code 120} or {@code 120px}; 0 for anything else. */
  static int pixels(String value) {
    if (value == null) {
      return 0;
    }
    String v = value.strip().toLowerCase(Locale.ROOT);
    if (v.endsWith("px")) {
      v = v.substring(0, v.length() - 2).strip();
    }
    return number(v, 1 << 14);
  }

  /** Parses {@code 50%}; 0 for anything else. */
  static int percent(String value) {
    if (value == null) {
      return 0;
    }
    String v = value.strip();
    return v.endsWith("%") ? number(v.substring(0, v.length() - 1).strip(), 100) : 0;
  }

  private static int number(String digits, int max) {
    if (digits.isEmpty() || digits.length() > 6) {
      return 0;
    }
    for (int i = 0; i < digits.length(); i++) {
      if (digits.charAt(i) < '0' || digits.charAt(i) > '9') {
        return 0;
      }
    }
    return Math.min(max, Integer.parseInt(digits));
  }

  /**
   * Starts decoding the images a pane shows.
   *
   * @param requests the shown content's images
   * @param view the pane
   * @return the running decode's token, to cancel it; null when nothing runs
   */
  CancellationToken show(
      final List<MarkdownImageRequest> requests, final MarkdownPreviewView view) {
    if (requests.isEmpty()) {
      return null;
    }
    final MarkdownImageDecoder decoder = view.imageDecoder();
    final CancellationToken token = new CancellationToken();
    boolean started =
        workers.tryStart(
            Workers.Kind.IO,
            new Runnable() {
              @Override
              public void run() {
                decodeAll(requests, decoder, view, token);
              }
            });
    return started ? token : null;
  }

  @WorkerThread
  private void decodeAll(
      List<MarkdownImageRequest> requests,
      MarkdownImageDecoder decoder,
      final MarkdownPreviewView view,
      final CancellationToken token) {
    BooleanSupplier canceled =
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return token.isCanceled();
          }
        };
    for (final MarkdownImageRequest request : requests) {
      if (token.isCanceled()) {
        return;
      }
      final DecodedImage image = decode(request, decoder, canceled);
      ui.asyncExec(
          new Runnable() {
            @Override
            public void run() {
              if (!token.isCanceled()) {
                view.showImage(request, image);
              }
            }
          });
    }
  }

  @WorkerThread
  private DecodedImage decode(
      MarkdownImageRequest request, MarkdownImageDecoder decoder, BooleanSupplier canceled) {
    Path path = request.path();
    Path file = path.getFileName();
    String name = file == null ? "" : file.toString();
    if (name.toLowerCase(Locale.ROOT).endsWith(".svg")) {
      return null;
    }
    try {
      long size = Files.size(path);
      if (size > MAX_FILE_BYTES) {
        return null;
      }
      String key =
          path
              + "\n"
              + size
              + "\n"
              + Files.getLastModifiedTime(path).toMillis()
              + "\n"
              + request.width()
              + "x"
              + request.height()
              + "/"
              + request.percent()
              + "/"
              + decoder.maxWidth();
      DecodedImage hit = cached(key);
      if (hit != null) {
        return hit;
      }
      DecodedImage image = decoder.decode(Files.readAllBytes(path), request, canceled);
      if (image != null) {
        keep(key, image);
      }
      return image;
    } catch (IOException e) {
      return null;
    }
  }

  private synchronized DecodedImage cached(String key) {
    return cache.get(key);
  }

  private synchronized void keep(String key, DecodedImage image) {
    if (image.bytes() > CACHE_BYTES) {
      return;
    }
    DecodedImage old = cache.put(key, image);
    cached += image.bytes() - (old == null ? 0 : old.bytes());
    Iterator<DecodedImage> eldest = cache.values().iterator();
    while (cached > CACHE_BYTES && eldest.hasNext()) {
      cached -= eldest.next().bytes();
      eldest.remove();
    }
  }

  /**
   * Returns the bytes the cache holds, for tests.
   *
   * @return the decoded images' estimated heap
   */
  synchronized long cachedBytes() {
    return cached;
  }
}
