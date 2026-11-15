package io.github.ajdailey.qcaret.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.List;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * M12: macOS hands a cold Open With's files as open-document events in the first event-loop passes;
 * the start collects them before the first window. Linux raises no such event at startup, so the
 * events here come from queued tasks.
 */
@Tag("swt")
class LaunchDocumentsTest {
  @Test
  void theQueuedEventsAreCollectedInOrder() {
    Display display = new Display();
    try {
      final LaunchDocuments launch = new LaunchDocuments();
      final Path first = Path.of("mock", "first.txt").toAbsolutePath();
      final Path second = Path.of("mock", "second.txt").toAbsolutePath();
      display.asyncExec(
          new Runnable() {
            @Override
            public void run() {
              launch.handleEvent(event(first.toString()));
              launch.handleEvent(event(""));
              launch.handleEvent(event("bad\0name"));
              launch.handleEvent(event(null));
            }
          });
      display.asyncExec(
          new Runnable() {
            @Override
            public void run() {
              launch.handleEvent(event(second.toString()));
            }
          });
      assertEquals(List.of(first, second), LaunchDocuments.collect(display, launch));
    } finally {
      display.dispose();
    }
  }

  @Test
  void aStartWithoutEventsCollectsNothing() {
    Display display = new Display();
    try {
      assertEquals(List.of(), LaunchDocuments.collect(display));
    } finally {
      display.dispose();
    }
  }

  private static Event event(String text) {
    Event event = new Event();
    event.text = text;
    return event;
  }
}
