package io.github.ajdailey.qcaret.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.cli.FileRequest;
import io.github.ajdailey.qcaret.app.cli.SessionMode;
import io.github.ajdailey.qcaret.app.cli.StartupRequest;
import io.github.ajdailey.qcaret.ui.swt.platform.PlatformTraits;
import java.nio.file.Path;
import java.util.List;
import org.eclipse.swt.widgets.Display;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * G2-D: on macOS a launch that hands off and names nothing takes the files of open-document events
 * first. Linux raises no such event, so the wait here ends at its first bound with no files; the
 * collection and stop rule are in {@code DocumentEventLoopTest}.
 */
@Tag("swt")
class HandoffDocumentsTest {
  @TempDir Path temp;

  private static StartupRequest request(List<FileRequest> files, SessionMode session, Path diff) {
    return new StartupRequest(
        files, null, null, null, null, null, false, false, session, diff, diff);
  }

  @Test
  void onlyALaunchThatNamesNothingTakesTheSystemsFiles() {
    Path file = temp.resolve("mock.txt");
    assertTrue(HandoffDocuments.namesNothing(request(List.of(), SessionMode.DEFAULT, null)));
    assertFalse(
        HandoffDocuments.namesNothing(
            request(List.of(new FileRequest(file)), SessionMode.DEFAULT, null)));
    assertFalse(HandoffDocuments.namesNothing(request(List.of(), SessionMode.RESTORE, null)));
    assertFalse(HandoffDocuments.namesNothing(request(List.of(), SessionMode.SKIP, null)));
    assertFalse(HandoffDocuments.namesNothing(request(List.of(), SessionMode.DEFAULT, file)));
  }

  @Test
  void theSystemsFilesBecomeTheRequestsFiles() {
    StartupRequest empty = request(List.of(), SessionMode.DEFAULT, null);
    assertSame(empty, HandoffDocuments.withFiles(empty, List.of()));
    Path first = temp.resolve("first.txt");
    Path second = temp.resolve("second.txt");
    assertEquals(
        List.of(new FileRequest(first), new FileRequest(second)),
        HandoffDocuments.withFiles(empty, List.of(first, second)).files());
  }

  @Test
  void aLaunchThatNamesAFileOrRunsWithoutEventsIsHandedAsItIs() {
    StartupRequest named =
        request(List.of(new FileRequest(temp.resolve("mock.txt"))), SessionMode.DEFAULT, null);
    assertSame(
        named,
        HandoffDocuments.beforeHandoff(named, PlatformTraits.of("cocoa", false).launchDocuments()));
    StartupRequest empty = request(List.of(), SessionMode.DEFAULT, null);
    assertSame(
        empty,
        HandoffDocuments.beforeHandoff(empty, PlatformTraits.of("win32", false).launchDocuments()));
    assertSame(empty, HandoffDocuments.beforeHandoff(empty));
  }

  /** A launch whose handoff then fails opens a window on a second Display. */
  @Test
  void theWaitDisposesItsDisplay() {
    StartupRequest empty = request(List.of(), SessionMode.DEFAULT, null);
    assertSame(
        empty,
        HandoffDocuments.beforeHandoff(empty, PlatformTraits.of("cocoa", false).launchDocuments()));
    Display next = new Display();
    try {
      assertFalse(next.isDisposed());
    } finally {
      next.dispose();
    }
  }
}
