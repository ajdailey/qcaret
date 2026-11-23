package io.github.ajdailey.qcaret.app.cli;

import io.github.ajdailey.qcaret.core.markdown.HeadingPath;
import java.util.ArrayList;
import java.util.List;

/**
 * Splits {@code file.md#heading} into a file and its {@link FileRequest#heading}, before a launch
 * hands its files on. A path naming an existing file keeps its {@code #} ({@link
 * HeadingPath#resolve}). Reads the file system.
 */
public final class HeadingFiles {
  private HeadingFiles() {}

  /**
   * Returns a launch with each file's heading split off.
   *
   * @param request the parsed launch
   * @return the launch; {@code request} itself when no file names a heading
   */
  public static StartupRequest resolve(StartupRequest request) {
    List<FileRequest> files = new ArrayList<FileRequest>();
    boolean changed = false;
    for (FileRequest file : request.files()) {
      HeadingPath split = file.heading() == null ? HeadingPath.resolve(file.path()) : null;
      if (split == null || split.heading() == null) {
        files.add(file);
      } else {
        changed = true;
        files.add(
            new FileRequest(
                split.path(),
                file.line(),
                file.column(),
                file.readOnly(),
                file.encoding(),
                split.heading()));
      }
    }
    return changed ? request.withFiles(files) : request;
  }
}
