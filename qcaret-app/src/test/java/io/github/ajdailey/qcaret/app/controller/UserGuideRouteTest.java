package io.github.ajdailey.qcaret.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.config.SettingsMode;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * User Guide in each settings mode, over a view with a guide window: per-user mode shows the
 * window, and explicit and portable mode on Linux open the copy without asking the view for a
 * window.
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
    boolean linux = "Linux".equals(System.getProperty("os.name"));
    SettingsMode[] modes = {SettingsMode.EXPLICIT, SettingsMode.PORTABLE};
    for (SettingsMode mode : modes) {
      final Harness harness = new Harness(temp.resolve(mode.name()), mode);
      try {
        harness.view.guideWindow = true;
        harness.application.execute(CommandId.USER_GUIDE);
        harness.await(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return harness.view.guides.size() + harness.view.launched.size() == 1;
              }
            },
            "guide in " + mode);
        if (linux) {
          assertEquals(List.of(), harness.view.guides, mode.name());
          assertEquals(1, harness.view.launched.size(), mode.name());
          assertTrue(harness.view.launched.get(0).endsWith("guide.html"), mode.name());
        } else {
          assertEquals(1, harness.view.guides.size(), mode.name());
          assertEquals(List.of(), harness.view.launched, mode.name());
        }
      } finally {
        harness.application.documents().dispose();
        harness.close();
      }
    }
  }
}
