package io.github.ajdailey.qcaret.launcher;

import io.github.ajdailey.qcaret.app.config.ErrorExitMarker;
import io.github.ajdailey.qcaret.app.recovery.RecoveryStore;
import io.github.ajdailey.qcaret.core.text.LogText;
import java.io.IOException;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What a start does about the {@link ErrorExitMarker}, decided before the Display. A start that
 * restores the session and finds the marker skips the session's windows, moves the snapshot files
 * into a recovery directory so the recovery dialog offers them, and deletes the marker. A start
 * that finds the marker pending shows the notice; one that does not restore rewrites the marker as
 * shown.
 *
 * @param skipped true when the session's windows are not restored
 * @param notice true when the start shows the notice
 */
record ErrorExitStart(boolean skipped, boolean notice) {
  private static final Logger LOG = LoggerFactory.getLogger(ErrorExitStart.class);

  /**
   * Reads the marker and updates it for this start.
   *
   * @param marker the marker
   * @param restore true when this start restores and owns the session
   * @param settings the settings directory
   * @param now the start time in milliseconds, which names the recovery directory
   * @return what the start does
   */
  static ErrorExitStart begin(ErrorExitMarker marker, boolean restore, Path settings, long now) {
    ErrorExitMarker.State state = marker.read();
    boolean skipped = restore && state != ErrorExitMarker.State.ABSENT;
    boolean notice = state == ErrorExitMarker.State.PENDING;
    if (skipped) {
      LOG.info("The last run closed after an internal error; its session windows are skipped");
      moveSnapshots(settings, now);
      marker.clear();
    } else if (notice) {
      LOG.info("The last run closed after an internal error");
      marker.markShown();
    }
    return new ErrorExitStart(skipped, notice);
  }

  private static void moveSnapshots(Path settings, long now) {
    try {
      int moved = RecoveryStore.moveSnapshotsToRecovery(settings, "snapshot-" + now);
      if (moved > 0) {
        LOG.info("{} snapshot buffers moved to recovery", moved);
      }
    } catch (IOException e) {
      LOG.warn("Cannot move the snapshot buffers to recovery: {}", LogText.oneLine(e.getMessage()));
    }
  }
}
