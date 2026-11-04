package io.github.ajdailey.qcaret.app.cli;

import java.nio.file.Path;

/**
 * A file to open.
 *
 * @param path the path as given, resolved against the working directory and not yet validated
 */
public record FileRequest(Path path) {}
