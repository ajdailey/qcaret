package io.github.ajdailey.qcaret.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.core.document.Document;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A checksum list saved over a file open in a tab, in this window or another, reaches that tab at
 * once as an external change, as Save a Copy As does. Polling is off and the tab opens the file
 * through a link in another folder, so no directory event reports the write either. Skipped where
 * the platform refuses links (Windows without the privilege).
 */
class ChecksumSaveOverTabTest {
  private static final String LIST =
      "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad  abc.txt\n";

  @TempDir Path temp;

  private Windows windows;
  private ApplicationController first;
  private Path dir;
  private Path target;
  private Path link;

  @BeforeEach
  void setUp() throws Exception {
    windows = new Windows(temp, false);
    first = windows.first();
    first.documents().setPollSeconds(3600);
    dir = Files.createDirectory(temp.resolve("work")).toRealPath();
    target = Files.writeString(dir.resolve("b.txt"), "two\n");
    link = Files.createDirectory(temp.resolve("links")).resolve("b.txt");
    try {
      Files.createSymbolicLink(link, target);
    } catch (UnsupportedOperationException | IOException e) {
      Assumptions.abort("symbolic links unavailable: " + e);
    }
  }

  @AfterEach
  void tearDown() throws Exception {
    windows.close();
  }

  /** Hashes a file holding {@code abc} in the first window and saves the list over the target. */
  private void saveListOverTarget() throws Exception {
    FakeView view = windows.view(first);
    Path file = Files.writeString(dir.resolve("abc.txt"), "abc", StandardCharsets.US_ASCII);
    first.execute(CommandId.SHA256_FILES);
    view.openChooser.accept(List.of(file));
    final FakeView.Checksums shown = view.checksums;
    windows.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return shown.status != null;
          }
        },
        "the list");
    view.saveChooser = null;
    shown.input.saveAs();
    assertNotNull(view.saveChooser);
    view.saveChooser.accept(target);
  }

  @Test
  void aCleanTabInThisWindowReloads() throws Exception {
    windows.open(first, link);
    final Document held = first.documents().activeDocument();
    saveListOverTarget();
    windows.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return LIST.equals(held.text().toString());
          }
        },
        "the tab reloaded");
    windows.settle("reload");
    assertFalse(held.isDirty());
  }

  @Test
  void aDirtyTabInAnotherWindowIsAsked() throws Exception {
    ApplicationController second = windows.second();
    second.documents().setPollSeconds(3600);
    windows.open(second, link);
    second.documents().activeEditor().type("mine ");
    final Document held = second.documents().activeDocument();
    final FakeView asked = windows.view(second);
    saveListOverTarget();
    windows.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !asked.changePrompts.isEmpty();
          }
        },
        "the change question");
    assertEquals(List.of("b.txt"), asked.changePrompts);
    assertEquals("mine two\n", held.text().toString());
    assertTrue(held.isDirty());
    assertEquals(LIST, Files.readString(target));
  }
}
