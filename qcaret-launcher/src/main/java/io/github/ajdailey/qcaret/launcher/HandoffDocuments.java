package io.github.ajdailey.qcaret.launcher;

import io.github.ajdailey.qcaret.app.cli.FileRequest;
import io.github.ajdailey.qcaret.app.cli.SessionMode;
import io.github.ajdailey.qcaret.app.cli.StartupRequest;
import io.github.ajdailey.qcaret.core.text.LogText;
import io.github.ajdailey.qcaret.ui.swt.SwtUiDispatcher;
import io.github.ajdailey.qcaret.ui.swt.platform.DocumentEventLoop;
import io.github.ajdailey.qcaret.ui.swt.platform.LaunchDocuments;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.swt.SWTError;
import org.eclipse.swt.widgets.Display;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The files the system hands a launch that names nothing as open-document events. On macOS a Finder
 * Open With can start a second registered copy with no file arguments while another instance is the
 * primary; {@link #beforeHandoff} takes the events through {@link LaunchDocuments}, so the handoff
 * carries the files. A launch that becomes the primary takes them in {@link Gui}.
 */
final class HandoffDocuments {
  private static final Logger LOG = LoggerFactory.getLogger(HandoffDocuments.class);

  private HandoffDocuments() {}

  /**
   * Reports whether a launch names no file, diff or session mode.
   *
   * @param request the command line
   * @return true when the system's open-document events supply the files
   */
  static boolean namesNothing(StartupRequest request) {
    return request.files().isEmpty()
        && request.diffLeft() == null
        && request.session() == SessionMode.DEFAULT;
  }

  /**
   * Returns the command line with the given files, or the command line itself when there are none.
   *
   * @param request the command line
   * @param paths the files
   * @return the request
   */
  static StartupRequest withFiles(StartupRequest request, List<Path> paths) {
    if (paths.isEmpty()) {
      return request;
    }
    List<FileRequest> files = new ArrayList<FileRequest>();
    for (Path path : paths) {
      files.add(new FileRequest(path));
    }
    return request.withFiles(files);
  }

  /**
   * Returns the request to hand to the running primary: on macOS a launch that names nothing waits
   * for open-document events ({@link LaunchDocuments#await}) and carries their files.
   *
   * @param request the command line
   * @return the request with the system's files, or the command line itself
   */
  static StartupRequest beforeHandoff(StartupRequest request) {
    return beforeHandoff(request, LaunchDocuments.PLATFORM);
  }

  /**
   * Returns the request to hand off; see {@link #beforeHandoff(StartupRequest)}.
   *
   * @param request the command line
   * @param events the platform's open-document events
   * @return the request
   */
  static StartupRequest beforeHandoff(StartupRequest request, LaunchDocuments events) {
    if (!namesNothing(request)) {
      return request;
    }
    List<Path> files = new ArrayList<Path>();
    for (String name : events.await(HandoffDocuments::waitForEvents)) {
      files.add(Path.of(name));
    }
    return withFiles(request, files);
  }

  /**
   * Runs a Display without a window until the open-document events stop, then disposes it. A
   * Display that cannot be created gives no files.
   */
  private static List<String> waitForEvents() {
    Display.setAppName("QCaret");
    Display display;
    try {
      display = new Display();
    } catch (UnsatisfiedLinkError | SWTError e) {
      LOG.debug("No open-document events without SWT: {}", LogText.oneLine(e.getMessage()));
      return List.of();
    }
    try {
      List<String> names = new ArrayList<String>();
      for (Path path : DocumentEventLoop.await(display, new SwtUiDispatcher(display))) {
        names.add(path.toString());
      }
      return names;
    } finally {
      display.dispose();
    }
  }
}
