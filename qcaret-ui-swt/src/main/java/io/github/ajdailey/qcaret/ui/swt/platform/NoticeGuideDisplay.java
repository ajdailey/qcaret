package io.github.ajdailey.qcaret.ui.swt.platform;

import java.nio.file.Path;

/** The guide named in a notice: GTK 4, where no {@code Browser} may be created. */
final class NoticeGuideDisplay implements GuideDisplay {
  @Override
  public boolean show(Path file, Shows shows) {
    shows.notice(file);
    return true;
  }
}
