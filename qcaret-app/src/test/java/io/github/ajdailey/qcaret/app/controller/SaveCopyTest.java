package io.github.ajdailey.qcaret.app.controller;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.core.document.Document;
import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * File > Save a Copy As: the document keeps its file, dirty state and history; the copy's bytes
 * match a Save As of the same text; a copy over another tab's file reaches that tab as an external
 * change; a failed write leaves the target as it was.
 */
class SaveCopyTest {
  /** ISO-8859-1 whose next encoder request, once armed, blocks until released. */
  private static final class Gate extends Charset {
    final AtomicBoolean armed = new AtomicBoolean();
    final CountDownLatch reached = new CountDownLatch(1);
    final CountDownLatch released = new CountDownLatch(1);

    Gate() {
      super("X-QCARET-COPY-GATE", null);
    }

    @Override
    public boolean contains(Charset other) {
      return StandardCharsets.ISO_8859_1.contains(other);
    }

    @Override
    public CharsetDecoder newDecoder() {
      return StandardCharsets.ISO_8859_1.newDecoder();
    }

    @Override
    public CharsetEncoder newEncoder() {
      if (armed.compareAndSet(true, false)) {
        reached.countDown();
        try {
          released.await(Harness.BOUND.toSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
      return StandardCharsets.ISO_8859_1.newEncoder();
    }
  }

  @TempDir Path temp;

  private Harness harness;
  private Path dir;

  @BeforeEach
  void create() throws Exception {
    harness = new Harness(temp);
    dir = Files.createDirectory(temp.resolve("work")).toRealPath();
  }

  @AfterEach
  void close() throws InterruptedException {
    harness.close();
  }

  private DocumentController documents() {
    return harness.application.documents();
  }

  /** Runs Save a Copy As onto {@code target} and waits until the copy ends. */
  private void copyTo(final Path target) throws InterruptedException {
    final int notices = harness.view.notices.size();
    final int infos = harness.view.infos.size();
    harness.view.saveChooser = null;
    harness.application.execute(CommandId.SAVE_COPY_AS);
    assertNotNull(harness.view.saveChooser);
    harness.view.saveChooser.accept(target);
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return harness.view.notices.size() > notices || harness.view.infos.size() > infos;
          }
        },
        "the copy's notice");
    harness.awaitIdle("copy");
  }

  @Test
  void aCopyWithUnsavedEditsLeavesTheDocumentDirtyOnItsFile() throws Exception {
    Path a = Files.writeString(dir.resolve("a.txt"), "one\n");
    harness.open(a);
    documents().activeEditor().type("mine ");
    Document document = documents().activeDocument();
    Path b = dir.resolve("b.txt");
    copyTo(b);
    assertEquals("mine one\n", Files.readString(b));
    assertEquals("one\n", Files.readString(a));
    assertTrue(document.isDirty());
    assertTrue(document.canUndo());
    assertEquals(a, documents().activePath());
    assertEquals(1, documents().count());
    assertEquals(b, harness.application.recentFiles().paths().get(0));
    assertTrue(
        harness.view.notices.contains(UiText.SAVE_COPY_DONE.format("a.txt", b)),
        harness.view.notices.toString());
  }

  @Test
  void theCopyMatchesSaveAsOfTheSameText() throws Exception {
    Files.writeString(
        dir.resolve(".editorconfig"),
        "root = true\n[*]\ntrim_trailing_whitespace = true\ninsert_final_newline = true\n");
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    bytes.write(new byte[] {(byte) 0xff, (byte) 0xfe});
    bytes.write("one  \r\ntwo".getBytes(StandardCharsets.UTF_16LE));
    Path a = Files.write(dir.resolve("a.txt"), bytes.toByteArray());
    harness.open(a);
    documents().activeEditor().type("mine ");
    Document document = documents().activeDocument();
    Path copy = dir.resolve("copy.txt");
    copyTo(copy);
    assertEquals("mine one  \r\ntwo", document.text().toString());
    assertTrue(document.isDirty());

    Path saved = dir.resolve("saved.txt");
    documents().saveAs();
    harness.view.saveChooser.accept(saved);
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !document.isDirty();
          }
        },
        "Save As");
    harness.awaitIdle("Save As");
    byte[] expected = Files.readAllBytes(saved);
    assertEquals(
        "mine one\r\ntwo\r\n",
        new String(expected, 2, expected.length - 2, StandardCharsets.UTF_16LE));
    assertArrayEquals(expected, Files.readAllBytes(copy));
  }

  @Test
  void aCopyOverAnotherTabsFileReachesThatTabAsAnExternalChange() throws Exception {
    Path a = Files.writeString(dir.resolve("a.txt"), "one\n");
    Path b = Files.writeString(dir.resolve("b.txt"), "two\n");
    harness.open(b);
    harness.open(a);
    documents().setPollSeconds(3600);
    documents().activeEditor().type("mine ");
    final Document other = documents().documentAt(b);
    copyTo(b);
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return "mine one\n".equals(other.text().toString());
          }
        },
        "the other tab reloaded");
    harness.awaitIdle("reload");
    assertFalse(other.isDirty());
    assertEquals(a, documents().activePath());
    assertTrue(documents().activeDocument().isDirty());
  }

  @Test
  void theDocumentsOwnFileRunsSave() throws Exception {
    Path a = Files.writeString(dir.resolve("a.txt"), "one\n");
    harness.open(a);
    documents().activeEditor().type("mine ");
    final Document document = documents().activeDocument();
    harness.application.execute(CommandId.SAVE_COPY_AS);
    harness.view.saveChooser.accept(a);
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !document.isDirty();
          }
        },
        "saved");
    harness.awaitIdle("save");
    assertEquals("mine one\n", Files.readString(a));
  }

  @Test
  void aFailedCopyLeavesTheTargetAsItWasAndReports() throws Exception {
    Path a = Files.writeString(dir.resolve("a.txt"), "one\n");
    Path b = Files.writeString(dir.resolve("b.txt"), "old\n");
    if (b.getFileSystem().supportedFileAttributeViews().contains("posix")) {
      Files.setPosixFilePermissions(b, PosixFilePermissions.fromString("r--r--r--"));
    } else {
      Files.setAttribute(b, "dos:readonly", true);
    }
    assumeFalse(Files.isWritable(b), "the user writes every file");
    harness.open(a);
    documents().activeEditor().type("mine ");
    int infos = harness.view.infos.size();
    copyTo(b);
    assertEquals("old\n", Files.readString(b));
    assertEquals(infos + 1, harness.view.infos.size(), harness.view.infos.toString());
    assertTrue(
        harness.view.infos.get(infos).contains(UiText.SAVE_FAILED.format("b.txt", "")),
        harness.view.infos.toString());
    assertTrue(documents().activeDocument().isDirty());
    assertEquals(List.of(a), harness.application.recentFiles().paths());
  }

  @Test
  void aCanceledCopyLeavesTheTargetAndTheDocumentAsTheyWere() throws Exception {
    Path a = Files.writeString(dir.resolve("a.txt"), "one\n");
    Path b = Files.writeString(dir.resolve("b.txt"), "old\n");
    harness.open(a);
    final Gate gate = new Gate();
    documents().reopenWithEncoding(gate);
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return documents().activeDocument().encoding().charset().equals(gate);
          }
        },
        "reopened");
    harness.awaitIdle("reopen");
    documents().activeEditor().type("mine ");
    Document document = documents().activeDocument();
    int infos = harness.view.infos.size();
    int notices = harness.view.notices.size();
    gate.armed.set(true);
    harness.application.execute(CommandId.SAVE_COPY_AS);
    harness.view.saveChooser.accept(b);
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return gate.reached.getCount() == 0;
          }
        },
        "the write started");
    documents().cancelSave();
    gate.released.countDown();
    harness.awaitIdle("canceled copy");
    assertEquals("old\n", Files.readString(b));
    assertTrue(document.isDirty());
    assertEquals(a, documents().activePath());
    assertEquals(infos, harness.view.infos.size(), harness.view.infos.toString());
    assertEquals(notices, harness.view.notices.size(), harness.view.notices.toString());
    assertEquals(List.of(a), harness.application.recentFiles().paths());
  }
}
