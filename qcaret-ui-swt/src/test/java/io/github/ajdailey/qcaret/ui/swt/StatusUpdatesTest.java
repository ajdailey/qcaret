package io.github.ajdailey.qcaret.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.ajdailey.qcaret.app.view.ManualUiDispatcher;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class StatusUpdatesTest {
  private final ManualUiDispatcher ui = new ManualUiDispatcher();
  private final List<String> calls = new ArrayList<String>();
  private final StatusUpdates updates =
      new StatusUpdates(
          ui,
          new Runnable() {
            @Override
            public void run() {
              calls.add("show");
            }
          });

  @AfterEach
  void dispose() {
    ui.dispose();
  }

  @Test
  void requestsBeforeTheUpdateRunsShareIt() {
    updates.request();
    updates.request();
    assertEquals(List.of(), calls);
    ui.runPending();
    assertEquals(List.of("show"), calls);
    updates.request();
    ui.runPending();
    assertEquals(List.of("show", "show"), calls);
    assertEquals(3, updates.requests());
  }

  @Test
  void aHeldViewDefersRequestsToEachRelease() {
    updates.hold(true);
    for (int turn = 0; turn < 5; turn++) {
      updates.request();
      ui.runPending();
    }
    assertEquals(List.of(), calls);
    updates.hold(false);
    ui.runPending();
    assertEquals(List.of("show"), calls);
    assertEquals(1, updates.shown());
    assertEquals(1, updates.releases());
  }

  @Test
  void anUpdatePostedBeforeAHoldWaitsForTheRelease() {
    updates.request();
    updates.hold(true);
    ui.runPending();
    assertEquals(List.of(), calls);
    updates.hold(false);
    ui.runPending();
    assertEquals(List.of("show"), calls);
  }

  @Test
  void aReleaseWithNothingWaitingPostsNothing() {
    updates.hold(true);
    updates.hold(false);
    assertEquals(0, ui.runPending());
    assertEquals(List.of(), calls);
  }
}
