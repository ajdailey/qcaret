package io.github.ajdailey.qcaret.ui.swt.platform;

import java.nio.file.Path;
import java.util.List;
import org.eclipse.swt.widgets.Display;

/** Document events on win32 and GTK: the system passes files as arguments, so there are none. */
final class NativeLaunchDocuments implements LaunchDocuments {
  @Override
  public List<Path> collect(Display display) {
    return List.of();
  }

  @Override
  public List<String> await(Wait wait) {
    return List.of();
  }
}
