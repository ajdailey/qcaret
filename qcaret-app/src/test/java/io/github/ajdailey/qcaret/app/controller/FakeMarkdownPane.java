package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.view.DecodedImage;
import io.github.ajdailey.qcaret.app.view.MarkdownImageDecoder;
import io.github.ajdailey.qcaret.app.view.MarkdownImageRequest;
import io.github.ajdailey.qcaret.app.view.MarkdownPreviewContent;
import io.github.ajdailey.qcaret.app.view.MarkdownPreviewInput;
import io.github.ajdailey.qcaret.app.view.MarkdownPreviewView;
import io.github.ajdailey.qcaret.app.view.MarkdownViewMode;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/** A Markdown preview pane that records display requests; images decode to their byte count. */
class FakeMarkdownPane implements MarkdownPreviewView {
  final MarkdownPreviewInput input;
  final List<MarkdownViewMode> modes = new ArrayList<MarkdownViewMode>();
  final List<MarkdownPreviewContent> shown = new ArrayList<MarkdownPreviewContent>();
  final List<int[]> notices = new ArrayList<int[]>();
  final List<Integer> scrolls = new ArrayList<Integer>();
  final List<String> anchors = new ArrayList<String>();
  final List<int[]> selects = new ArrayList<int[]>();
  final List<MarkdownImageRequest> images = new ArrayList<MarkdownImageRequest>();
  final List<DecodedImage> decoded = new ArrayList<DecodedImage>();

  FakeMarkdownPane(MarkdownPreviewInput input) {
    this.input = input;
  }

  /** An image of one pixel row per file byte. */
  record Decoded(int width, int height, long bytes) implements DecodedImage {}

  @Override
  public void showMode(MarkdownViewMode mode) {
    modes.add(mode);
  }

  @Override
  public void showRendered(MarkdownPreviewContent content) {
    shown.add(content);
  }

  @Override
  public void showSizeNotice(int chars, int limit) {
    notices.add(new int[] {chars, limit});
  }

  @Override
  public void scrollToLine(int line) {
    scrolls.add(line);
  }

  @Override
  public void selectRendered(int start, int end) {
    selects.add(new int[] {start, end});
  }

  @Override
  public void scrollToAnchor(String id) {
    anchors.add(id);
  }

  @Override
  public MarkdownImageDecoder imageDecoder() {
    return new MarkdownImageDecoder() {
      @Override
      public int maxWidth() {
        return 400;
      }

      @Override
      public DecodedImage decode(
          byte[] bytes, MarkdownImageRequest request, BooleanSupplier canceled) {
        return bytes.length == 0 ? null : new Decoded(1, bytes.length, bytes.length);
      }
    };
  }

  @Override
  public void showImage(MarkdownImageRequest request, DecodedImage image) {
    images.add(request);
    decoded.add(image);
  }

  /** Returns the last mode shown, Source before any. */
  MarkdownViewMode mode() {
    return modes.isEmpty() ? MarkdownViewMode.SOURCE : modes.get(modes.size() - 1);
  }
}
