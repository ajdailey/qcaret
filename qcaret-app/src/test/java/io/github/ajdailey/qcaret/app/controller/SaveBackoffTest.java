package io.github.ajdailey.qcaret.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.ajdailey.qcaret.app.session.SessionStore;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The retry delay of a failed session write. */
class SaveBackoffTest {
  @Test
  void theDelayDoublesPerFailureUpToTheCapAndAnyOtherOutcomeEndsTheRun() {
    SaveBackoff backoff = new SaveBackoff();
    assertEquals(0, backoff.delay());
    List<Integer> delays = new ArrayList<Integer>();
    for (int i = 0; i < 7; i++) {
      backoff.record(SessionStore.Write.FAILED);
      delays.add(backoff.delay());
    }
    assertEquals(List.of(2000, 4000, 8000, 16000, 32000, 60000, 60000), delays);
    for (int i = 0; i < 100; i++) {
      backoff.record(SessionStore.Write.FAILED);
    }
    assertEquals(SaveBackoff.MAX_MILLIS, backoff.delay());
    backoff.record(SessionStore.Write.WRITTEN);
    assertEquals(0, backoff.delay());
    backoff.record(SessionStore.Write.FAILED);
    assertEquals(SessionController.DELAY_MILLIS, backoff.delay());
    backoff.record(SessionStore.Write.REFUSED);
    assertEquals(0, backoff.delay());
  }
}
