package io.github.ajdailey.qcaret.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.view.BinaryChoice;
import io.github.ajdailey.qcaret.app.view.WorkspaceNode;
import io.github.ajdailey.qcaret.core.document.LoadCost;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Hex Viewer windows through the application controller: the binary file prompt's three answers,
 * the too-large refusal's button, File > Open in Hex Viewer, the Workspace row action, a file
 * changed on disk with Reload, and closing a window.
 */
class HexViewerTest {
  @TempDir Path temp;

  private Harness harness;
  private DocumentController documents;

  @BeforeEach
  void create() {
    harness = new Harness(temp);
    documents = harness.application.documents();
  }

  @AfterEach
  void close() throws InterruptedException {
    documents.dispose();
    harness.close();
  }

  private FakeHexView awaitViewer(final int count) throws InterruptedException {
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return harness.view.hexViewers.size() == count;
          }
        },
        count + " viewers");
    harness.awaitIdle("viewer");
    return harness.view.hexViewers.get(count - 1);
  }

  private void awaitPrompt() throws InterruptedException {
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return harness.view.binaryAnswer != null;
          }
        },
        "binary prompt");
  }

  @Test
  void theBinaryPromptOpensTheViewerATextTabOrNothing() throws Exception {
    final Path binary = Files.write(temp.resolve("data.bin"), ShellCommandsTest.BINARY);
    documents.open(List.of(binary));
    awaitPrompt();
    assertEquals("data.bin", harness.view.binaryName);
    harness.view.binaryAnswer.accept(BinaryChoice.HEX_VIEWER);
    final FakeHexView window = awaitViewer(1);
    assertEquals(UiText.HEX_VIEWER_TITLE.format("data.bin"), window.title);
    assertEquals(0, documents.count());
    assertEquals(ShellCommandsTest.BINARY.length, window.rows.size());
    window.input.resized(2);
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return window.paint() == 2;
          }
        },
        "rows read");
    assertEquals((byte) 0x89, window.input.row(0).bytes()[0]);

    harness.view.binaryAnswer = null;
    documents.open(List.of(binary));
    awaitPrompt();
    harness.view.binaryAnswer.accept(BinaryChoice.CANCEL);
    harness.awaitIdle("cancel");
    assertEquals(0, documents.count());
    assertEquals(1, harness.view.hexViewers.size());

    harness.view.binaryAnswer = null;
    documents.open(List.of(binary));
    awaitPrompt();
    harness.view.binaryAnswer.accept(BinaryChoice.TEXT);
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return documents.count() == 1;
          }
        },
        "binary tab");
    assertTrue(documents.activeBinary());
  }

  @Test
  void theTooLargeRefusalOffersTheViewer() throws Exception {
    Path big = Files.write(temp.resolve("big.log"), new byte[] {1, 2, 3});
    documents.new Open().tooLarge(big, new LoadCost(3L << 30, 0, 0, 3L << 30), 0, 1L << 30);
    assertNotNull(harness.view.tooLarge);
    assertTrue(harness.view.tooLarge.startsWith("big.log"), harness.view.tooLarge);
    harness.view.tooLargeOpen.run();
    assertEquals(3, awaitViewer(1).rows.size());
  }

  @Test
  void openInHexViewerAndTheWorkspaceRowOpenWindows() throws Exception {
    Path a = Files.write(temp.resolve("a.bin"), new byte[] {1, 2});
    Path b = Files.write(temp.resolve("b.bin"), new byte[] {3});
    assertTrue(new CommandStates(harness.application).enabled(CommandId.OPEN_IN_HEX_VIEWER));
    harness.application.execute(CommandId.OPEN_IN_HEX_VIEWER);
    assertNotNull(harness.view.openChooser);
    harness.view.openChooser.accept(List.of(a));
    assertEquals(2, awaitViewer(1).rows.size());
    harness
        .application
        .workspace()
        .openInHexViewer(new WorkspaceNode(b, "b.bin", WorkspaceNode.Kind.FILE));
    assertEquals(1, awaitViewer(2).rows.size());
    harness
        .application
        .workspace()
        .openInHexViewer(new WorkspaceNode(temp, "dir", WorkspaceNode.Kind.DIRECTORY));
    harness.awaitIdle("directory row");
    assertEquals(2, harness.view.hexViewers.size());
    harness.view.openChooser.accept(List.of(temp.resolve("missing.bin")));
    harness.awaitIdle("missing file");
    assertEquals(2, harness.view.hexViewers.size());
    String failure = harness.view.infos.get(harness.view.infos.size() - 1);
    assertTrue(failure.contains("missing.bin"), failure);
  }

  @Test
  void aChangedFileShowsReloadWhichReadsItAgain() throws Exception {
    final Path file = Files.write(temp.resolve("a.bin"), new byte[] {1, 2, 3, 4});
    documents.hexViewers().open(file);
    final FakeHexView window = awaitViewer(1);
    assertNull(window.notice);
    window.input.focusChanged(true);
    harness.awaitIdle("unchanged check");
    assertNull(window.notice);
    Files.write(file, new byte[] {9, 9, 9, 9, 9, 9});
    window.input.focusChanged(true);
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return window.notice != null;
          }
        },
        "changed notice");
    assertEquals(UiText.HEX_CHANGED_ON_DISK.text(), window.notice);
    assertEquals(UiText.HEX_RELOAD.text(), window.noticeAction);
    window.input.noticeAction();
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return window.rows.size() == 6 && window.notice == null;
          }
        },
        "reloaded");
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return window.input.row(0) != null;
          }
        },
        "reloaded row");
    assertEquals(9, window.input.row(0).bytes()[0]);
  }

  @Test
  void closingTheWindowEndsItsController() throws Exception {
    Path file = Files.write(temp.resolve("a.bin"), new byte[] {1});
    documents.hexViewers().open(file);
    FakeHexView window = awaitViewer(1);
    assertEquals(1, documents.hexViewers().controllers().size());
    window.input.attach(null);
    assertTrue(documents.hexViewers().controllers().isEmpty());
    assertFalse(harness.view.hexViewers.isEmpty());
  }
}
