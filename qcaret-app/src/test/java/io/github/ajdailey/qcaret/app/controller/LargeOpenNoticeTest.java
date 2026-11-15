package io.github.ajdailey.qcaret.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.ajdailey.qcaret.app.i18n.UiText;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * G1 L7: the editor of a large document is built on the UI thread after the status bar notice and
 * the busy cursor are set, and both clear once it is built.
 */
class LargeOpenNoticeTest {
  @TempDir Path temp;

  private Harness harness;

  @BeforeEach
  void create() {
    harness = new Harness(temp);
  }

  @AfterEach
  void close() throws InterruptedException {
    harness.close();
  }

  @Test
  void theNoticeAndTheBusyStateHoldWhileTheEditorIsBuilt() throws Exception {
    Path file =
        Files.writeString(temp.resolve("big.txt"), "x\n".repeat(DocumentOpener.SLOW_PANE_LINES));
    final List<String> atBuild = new ArrayList<String>();
    harness.view.beforeAddTab =
        new Runnable() {
          @Override
          public void run() {
            atBuild.add(harness.view.notices.get(harness.view.notices.size() - 1));
            atBuild.add("active " + (harness.view.active.get() > 0));
          }
        };
    harness.open(file);
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return harness.view.active.get() == 0;
          }
        },
        "idle");
    String notice = UiText.LOAD_PREPARING.format("big.txt", DocumentOpener.SLOW_PANE_LINES + 1);
    assertEquals(List.of(notice, "active true"), atBuild);
    assertEquals("", harness.view.notices.get(harness.view.notices.size() - 1));
    assertEquals("Preparing big.txt for display: 500,001 lines", notice);
  }
}
