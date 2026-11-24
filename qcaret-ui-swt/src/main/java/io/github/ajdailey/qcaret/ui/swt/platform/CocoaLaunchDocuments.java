package io.github.ajdailey.qcaret.ui.swt.platform;

import java.nio.file.Path;
import java.util.List;
import org.eclipse.swt.widgets.Display;

/**
 * Document events on cocoa ({@link DocumentEventLoop}). A Finder Open With can start a second
 * registered copy with no file arguments while another instance is the primary, so the handoff
 * waits for the events first.
 */
final class CocoaLaunchDocuments implements LaunchDocuments {
  @Override
  public List<Path> collect(Display display) {
    return DocumentEventLoop.collect(display);
  }

  @Override
  public List<String> await(Wait wait) {
    return wait.run();
  }
}
