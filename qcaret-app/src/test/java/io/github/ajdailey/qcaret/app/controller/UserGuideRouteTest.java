package io.github.ajdailey.qcaret.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.config.SettingsMode;
import io.github.ajdailey.qcaret.core.util.os.OsTraits;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * User Guide in each settings mode, over a view with a guide window: per-user mode shows the
 * window, and explicit and portable mode on Linux open the copy without asking the view for a
 * window. The view's {@code system} sets the platform, so every platform's route runs on each host.
 */
class UserGuideRouteTest {
  @TempDir Path temp;

  @Test
  void aGuideWindowShowsTheCopyAndOpensNothingElseUntilItsBrowserFails() throws Exception {
    final Harness harness = new Harness(temp, SettingsMode.PER_USER);
    try {
      harness.view.guideWindow = true;
      harness.application.execute(CommandId.USER_GUIDE);
      harness.await(
          new BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
              return harness.view.guides.size() == 1;
            }
          },
          "guide window");
      assertTrue(harness.view.launched.isEmpty(), harness.view.launched.toString());
      Path guide = harness.view.guides.get(0);
      harness.application.showGuideElsewhere(guide);
      assertEquals(List.of(guide.toString()), harness.view.launched);
    } finally {
      harness.application.documents().dispose();
      harness.close();
    }
  }

  @Test
  void explicitAndPortableModeOpenTheCopyOutsideOnLinux() throws Exception {
    String[] systems = {"Linux", "Windows 11", "Mac OS X"};
    SettingsMode[] modes = {SettingsMode.EXPLICIT, SettingsMode.PORTABLE};
    for (String os : systems) {
      boolean linux = os.equals("Linux");
      for (SettingsMode mode : modes) {
        String name = os + " " + mode;
        final Harness harness = new Harness(temp.resolve(os.replace(' ', '-') + mode), mode);
        try {
          harness.view.guideWindow = true;
          harness.view.system = OsTraits.of(os);
          harness.application.execute(CommandId.USER_GUIDE);
          harness.await(
              new BooleanSupplier() {
                @Override
                public boolean getAsBoolean() {
                  return harness.view.guides.size() + harness.view.launched.size() == 1;
                }
              },
              "guide in " + name);
          if (linux) {
            assertEquals(List.of(), harness.view.guides, name);
            assertEquals(1, harness.view.launched.size(), name);
            assertTrue(harness.view.launched.get(0).endsWith("guide.html"), name);
          } else {
            assertEquals(1, harness.view.guides.size(), name);
            assertEquals(List.of(), harness.view.launched, name);
          }
        } finally {
          harness.application.documents().dispose();
          harness.close();
        }
      }
    }
  }
}
