package io.github.ajdailey.qcaret.app.controller;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.view.ManualUiDispatcher;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.document.Document;
import io.github.ajdailey.qcaret.core.document.LoadOptions;
import io.github.ajdailey.qcaret.core.testkit.Poll;
import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The saves that end in a notice, with the file as it was. */
class SaveRefusalTest {
  private static final Duration BOUND = Duration.ofSeconds(20);

  /** A declared windows-31j file; NEC row 13's ED 40 decodes to U+7E8A, which encodes to FA 5C. */
  static final byte[] NO_ROUND_TRIP = noRoundTrip();

  @TempDir Path temp;

  private final ManualUiDispatcher ui = new ManualUiDispatcher();
  private final FakeView view = new FakeView(ui);
  private final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
  private Workers workers;
  private DocumentController documents;

  private static byte[] noRoundTrip() {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.writeBytes(
        "<?xml version=\"1.0\" encoding=\"windows-31j\"?>\n".getBytes(StandardCharsets.US_ASCII));
    out.writeBytes(new byte[] {(byte) 0xed, 0x40, '\n'});
    return out.toByteArray();
  }

  @BeforeEach
  void setUp() {
    workers =
        new Workers(
            2,
            new Thread.UncaughtExceptionHandler() {
              @Override
              public void uncaughtException(Thread thread, Throwable error) {
                failure.set(error);
              }
            });
    documents =
        new DocumentController(
            workers, ui, view, new MemoryBudget(1L << 30, 50), LoadOptions.DEFAULTS);
  }

  @AfterEach
  void tearDown() throws Exception {
    documents.dispose();
    assertTrue(
        Poll.until(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return !documents.watching();
              }
            },
            BOUND));
    workers.close();
  }

  private void await(BooleanSupplier condition, String what) throws Exception {
    assertTrue(ui.runUntil(condition, BOUND), what);
  }

  private void idle() throws Exception {
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return view.active.get() == 0 && documents.loading() == 0;
          }
        },
        "idle");
    ui.runPending();
  }

  private Document open(Path file) throws Exception {
    final int before = view.tabs.size();
    documents.open(List.of(file));
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return view.tabs.size() == before + 1;
          }
        },
        "opened");
    idle();
    return documents.activeDocument();
  }

  private Document openNoRoundTrip(Path file) throws Exception {
    Files.write(file, NO_ROUND_TRIP);
    Document document = open(file);
    assertTrue(document.isReadOnly());
    assertFalse(document.isDirty());
    return document;
  }

  @Test
  void saveDoesNotRewriteAFileOpenedReadOnlyForItsBytes() throws Exception {
    Path file = temp.resolve("a.xml");
    openNoRoundTrip(file);
    int infos = view.infos.size();
    documents.save();
    idle();
    assertArrayEquals(NO_ROUND_TRIP, Files.readAllBytes(file));
    assertEquals(infos + 1, view.infos.size(), view.infos.toString());
    assertTrue(
        view.infos.get(infos).endsWith(UiText.SAVE_NO_ROUND_TRIP.format("a.xml", "windows-31j")),
        view.infos.toString());
  }

  @Test
  void clearingReadOnlyAcceptsTheConversion() throws Exception {
    Path file = temp.resolve("a.xml");
    openNoRoundTrip(file);
    documents.toggleReadOnly();
    documents.save();
    idle();
    byte[] now = Files.readAllBytes(file);
    assertEquals((byte) 0xfa, now[NO_ROUND_TRIP.length - 3]);
    assertEquals((byte) 0x5c, now[NO_ROUND_TRIP.length - 2]);
  }

  @Test
  void saveAsWritesAConvertedCopyAndSaveThenWritesIt() throws Exception {
    Path file = temp.resolve("a.xml");
    Document document = openNoRoundTrip(file);
    documents.saveAs();
    assertNotNull(view.saveChooser);
    final Path copy = temp.resolve("b.xml");
    view.saveChooser.accept(copy);
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return Files.exists(copy);
          }
        },
        "the copy");
    idle();
    assertArrayEquals(NO_ROUND_TRIP, Files.readAllBytes(file));
    byte[] converted = Files.readAllBytes(copy);
    assertEquals((byte) 0xfa, converted[NO_ROUND_TRIP.length - 3]);
    assertTrue(document.isReadOnly());
    int infos = view.infos.size();
    documents.save();
    idle();
    assertEquals(infos, view.infos.size(), view.infos.toString());
  }

  private static void removeWritePermission(Path file) throws Exception {
    if (file.getFileSystem().supportedFileAttributeViews().contains("posix")) {
      Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("r--r--r--"));
    } else {
      Files.setAttribute(file, "dos:readonly", true);
    }
  }

  @Test
  void saveDoesNotReplaceAFileWithoutWritePermission() throws Exception {
    Path file = Files.writeString(temp.resolve("a.txt"), "one\n");
    removeWritePermission(file);
    assumeFalse(Files.isWritable(file), "the user writes every file");
    Document document = open(file);
    documents.activeEditor().type("mine ");
    int infos = view.infos.size();
    documents.save();
    idle();
    assertEquals("one\n", Files.readString(file));
    assertTrue(document.isDirty());
    assertEquals(infos + 1, view.infos.size(), view.infos.toString());
    assertTrue(
        view.infos.get(infos).contains(UiText.SAVE_FAILED.format("a.txt", "")),
        view.infos.toString());
  }

  @Test
  void aSaveInAnEncodingWithoutAnEncoderReportsTheFailure() throws Exception {
    final Charset auto = Charset.forName("x-JISAutoDetect");
    assumeFalse(auto.canEncode());
    Path file = Files.writeString(temp.resolve("a.txt"), "one\n");
    open(file);
    documents.reopenWithEncoding(auto);
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return documents.activeDocument().encoding().charset().equals(auto);
          }
        },
        "reopened");
    idle();
    documents.toggleReadOnly();
    documents.activeEditor().type("mine ");
    final int infos = view.infos.size();
    documents.saveAs();
    assertNotNull(view.saveChooser);
    Path copy = temp.resolve("b.txt");
    view.saveChooser.accept(copy);
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return view.infos.size() > infos || failure.get() != null;
          }
        },
        "the failure");
    idle();
    assertNull(failure.get(), "uncaught on the worker");
    assertEquals(infos + 1, view.infos.size(), view.infos.toString());
    assertTrue(documents.activeDocument().isDirty());
    assertFalse(Files.exists(copy));
  }
}
