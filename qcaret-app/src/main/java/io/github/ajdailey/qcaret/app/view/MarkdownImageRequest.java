package io.github.ajdailey.qcaret.app.view;

import java.nio.file.Path;

/**
 * A local image of a render, the size it shows at resolved from its attributes. A dimension the
 * attributes leave out follows the image's aspect ratio; with neither given, the image shows at its
 * own size. Either way it shrinks to the pane's width.
 *
 * @param offset the rendered offset of its {@code MarkdownImage.PLACEHOLDER} char
 * @param path the file
 * @param alt the alt text
 * @param width pixels, 0 when not given
 * @param height pixels, 0 when not given
 * @param percent the share of the pane's width, 1 to 100; 0 when not given, and a pixel width wins
 */
public record MarkdownImageRequest(
    int offset, Path path, String alt, int width, int height, int percent) {}
