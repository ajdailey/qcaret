package io.github.ajdailey.qcaret.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.view.ManualUiDispatcher;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link LaunchCursor}: a launch an application took holds the busy cursor until its timer runs; a
 * refused one leaves it idle. {@link ManualUiDispatcher} queues the timer at once.
 */
class LaunchCursorTest {
  @TempDir Path temp;

  private final ManualUiDispatcher ui = new ManualUiDispatcher();
  private final FakeView view = new FakeView(ui);

  @Test
  void aLaunchHoldsTheCursorUntilItsTimer() {
    assertTrue(LaunchCursor.launch(view, ui, temp));
    assertTrue(LaunchCursor.launchUrl(view, ui, "https://example.invalid/"));
    assertEquals(List.of(temp.toString(), "https://example.invalid/"), view.launched);
    assertEquals(2, view.active.get());
    assertTrue(LaunchCursor.MILLIS < ManualUiDispatcher.LONG_MILLIS);
    assertEquals(2, ui.runPending());
    assertEquals(0, view.active.get());
  }

  @Test
  void aRefusedLaunchLeavesTheCursorIdle() {
    view.launchFails = true;
    assertFalse(LaunchCursor.launch(view, ui, temp));
    assertFalse(LaunchCursor.launchUrl(view, ui, "https://example.invalid/"));
    assertEquals(0, view.activations.get());
    assertEquals(0, ui.runPending());
  }
}
