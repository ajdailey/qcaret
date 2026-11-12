package io.github.ajdailey.qcaret.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.core.document.Selection;
import io.github.ajdailey.qcaret.core.document.SelectionSet;
import io.github.ajdailey.qcaret.core.io.ChecksumAlgorithm;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The Tools menu: Generate, Generate from Files, Generate from Selection and Verify Checksums. */
class ChecksumToolsTest {
  private static final String ABC_SHA256 =
      "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
  private static final String ABC_MD5 = "900150983cd24fb0d6963f7d28e17f72";

  @TempDir Path temp;

  private Harness harness;

  @BeforeEach
  void create() throws IOException {
    Files.createDirectory(temp.resolve("settings"));
    harness = new Harness(temp);
  }

  @AfterEach
  void close() throws InterruptedException {
    harness.close();
  }

  private static String sha256(byte[] bytes) throws NoSuchAlgorithmException {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }

  private FakeView.Checksums finished() throws InterruptedException {
    final FakeView.Checksums shown = harness.view.checksums;
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return shown.status != null;
          }
        },
        "finished");
    return shown;
  }

  @Test
  void generateHashesTheTextAsTypedAndCopies() throws Exception {
    harness.application.execute(CommandId.SHA256_TEXT);
    final FakeView.HashText shown = harness.view.hashText;
    assertEquals("SHA-256 of Text", shown.title);
    assertEquals(UiText.CHECKSUM_TEXT_NOTE.text(), shown.note);
    shown.input.textChanged("ab");
    shown.input.textChanged("abc");
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return ABC_SHA256.equals(shown.hex);
          }
        },
        "hashed");
    shown.input.copy();
    assertEquals(ABC_SHA256, harness.view.clipboard);
    harness.application.execute(CommandId.MD5_TEXT);
    assertTrue(harness.view.hashText.note.endsWith(UiText.CHECKSUM_WEAK.format("MD5")));
  }

  /**
   * Two files share a name, so every name is relative to the common folder; the third spans several
   * read buffers. The list saved in the common folder verifies.
   */
  @Test
  void generateFromFilesWritesSha256sumLinesAndSaves() throws Exception {
    Path a = Files.createDirectory(temp.resolve("a")).resolve("x.txt");
    Path b = Files.createDirectory(temp.resolve("b")).resolve("x.txt");
    Path big = temp.resolve("big.bin");
    Files.writeString(a, "abc", StandardCharsets.US_ASCII);
    Files.writeString(b, "", StandardCharsets.US_ASCII);
    byte[] bytes = new byte[5 * 64 * 1024 + 3];
    for (int i = 0; i < bytes.length; i++) {
      bytes[i] = (byte) (i * 7);
    }
    Files.write(big, bytes);
    harness.application.execute(CommandId.SHA256_FILES);
    harness.view.openChooser.accept(List.of(a, b, big));
    FakeView.Checksums shown = finished();
    assertEquals("SHA-256 of Files", shown.title);
    assertEquals("", shown.note);
    assertEquals(UiText.BUTTON_CHOOSE_FILES.text(), shown.chooseLabel);
    List<String> expected =
        List.of(
            ABC_SHA256 + "  a/x.txt",
            sha256(new byte[0]) + "  b/x.txt",
            sha256(bytes) + "  big.bin");
    assertEquals(expected, shown.lines);
    assertEquals(UiText.CHECKSUM_FILES_DONE.format(3, 0), shown.status);
    assertFalse(shown.progress.isEmpty());
    shown.input.copy();
    assertEquals(String.join("\n", expected) + "\n", harness.view.clipboard);
    shown.input.saveAs();
    assertEquals("SHA256SUMS", harness.view.saveName);
    final Path saved = temp.resolve("SHA256SUMS");
    harness.view.saveChooser.accept(saved);
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            try {
              return Files.readString(saved).equals(String.join("\n", expected) + "\n");
            } catch (IOException e) {
              return false;
            }
          }
        },
        "saved text");
    harness.application.execute(CommandId.VERIFY_CHECKSUMS);
    FakeView.Checksums verified = harness.view.checksums;
    verified.status = null;
    verified.input.choose();
    harness.view.openChooser.accept(List.of(saved));
    finished();
    assertEquals(List.of("a/x.txt: OK", "b/x.txt: OK", "big.bin: OK"), verified.lines);
  }

  /**
   * Windows-shaped elements (a drive root token and a short folder name): repeated names are the
   * path below the common folder joined with {@code /}; unique names stay bare; two drives keep the
   * root.
   */
  @Test
  void repeatedNamesAreRelativeToTheCommonFolder() {
    List<String> a = List.of("C:", "Users", "RUNNER~1", "a", "x.txt");
    List<String> b = List.of("C:", "Users", "RUNNER~1", "b", "x.txt");
    List<String> c = List.of("C:", "Users", "RUNNER~1", "c.bin");
    List<String> deep = List.of("C:", "Users", "RUNNER~1", "a", "d", "c.bin");
    assertEquals(List.of("a/x.txt", "b/x.txt", "c.bin"), FileChecksums.names(List.of(a, b, c)));
    assertEquals(List.of("x.txt", "c.bin"), FileChecksums.names(List.of(a, c)));
    assertEquals(List.of("c.bin", "a/d/c.bin"), FileChecksums.names(List.of(c, deep)));
    List<String> other = List.of("D:", "a", "x.txt");
    assertEquals(
        List.of("C:Users/RUNNER~1/a/x.txt", "D:a/x.txt"), FileChecksums.names(List.of(a, other)));
  }

  @Test
  void aFileThatCannotBeReadGetsNoLine() throws Exception {
    Path folder = Files.createDirectory(temp.resolve("folder"));
    Path ok = temp.resolve("ok.txt");
    Files.writeString(ok, "abc", StandardCharsets.US_ASCII);
    harness.application.execute(CommandId.MD5_FILES);
    harness.view.openChooser.accept(List.of(folder, ok));
    FakeView.Checksums shown = finished();
    assertEquals(List.of(ABC_MD5 + "  ok.txt"), shown.lines);
    assertTrue(shown.status.startsWith(UiText.CHECKSUM_FILES_DONE.format(1, 1)), shown.status);
    assertTrue(shown.note.contains("MD5"));
  }

  /** A run canceled mid-file posts nothing more; the next run's lines are the only ones shown. */
  @Test
  void aCanceledRunsLateLinesAreDropped() throws Exception {
    Path big = temp.resolve("big.bin");
    Files.write(big, new byte[32 * 1024 * 1024]);
    Path small = temp.resolve("small.txt");
    Files.writeString(small, "abc", StandardCharsets.US_ASCII);
    harness.application.execute(CommandId.SHA256_FILES);
    harness.view.openChooser.accept(List.of(big, small));
    FakeView.Checksums shown = harness.view.checksums;
    shown.input.cancel();
    assertEquals(UiText.CHECKSUM_CANCELED.text(), shown.status);
    shown.status = null;
    shown.input.choose();
    harness.view.openChooser.accept(List.of(small));
    finished();
    assertEquals(List.of(ABC_SHA256 + "  small.txt"), shown.lines);
    assertEquals(UiText.CHECKSUM_FILES_DONE.format(1, 0), shown.status);
  }

  /** OK, FAILED, MISSING, unreadable, a {@code *} name and a bad line, from the open document. */
  @Test
  void verifyChecksTheDocumentsList() throws Exception {
    Files.writeString(temp.resolve("good.txt"), "abc", StandardCharsets.US_ASCII);
    Files.writeString(temp.resolve("bad.txt"), "abd", StandardCharsets.US_ASCII);
    Files.writeString(temp.resolve("star.bin"), "abc", StandardCharsets.US_ASCII);
    Files.createDirectory(temp.resolve("folder"));
    String list =
        ABC_SHA256
            + "  good.txt\r\n"
            + ABC_SHA256
            + "  bad.txt\r\n"
            + "this line is not a checksum\r\n"
            + ABC_SHA256
            + "  gone.txt\r\n"
            + ABC_SHA256
            + "  folder\r\n"
            + ABC_MD5
            + " *star.bin\r\n";
    Path file = temp.resolve("SHA256SUMS");
    Files.writeString(file, list, StandardCharsets.UTF_8);
    harness.open(file);
    harness.application.execute(CommandId.VERIFY_CHECKSUMS);
    FakeView.Checksums shown = finished();
    // The document holds the real path: /private/var on macOS, the long user name on Windows.
    assertEquals(UiText.CHECKSUM_LIST.format(file.toRealPath()), shown.source);
    assertEquals(6, shown.lines.size(), shown.lines.toString());
    assertEquals("good.txt: OK", shown.lines.get(0));
    assertEquals("bad.txt: FAILED", shown.lines.get(1));
    assertEquals("gone.txt: MISSING", shown.lines.get(2));
    assertTrue(shown.lines.get(3).startsWith("folder: unreadable ("), shown.lines.get(3));
    assertEquals("star.bin: OK", shown.lines.get(4));
    assertEquals(UiText.CHECKSUM_BAD_LINE.format(3), shown.lines.get(5));
    assertEquals(UiText.CHECKSUM_VERIFY_DONE.format(2, 1, 1, 1, 1), shown.status);
  }

  @Test
  void verifyReadsAChosenList() throws Exception {
    Path folder = Files.createDirectory(temp.resolve("lists"));
    Files.writeString(folder.resolve("good.txt"), "abc", StandardCharsets.US_ASCII);
    Path list = folder.resolve("MD5SUMS");
    Files.writeString(list, ABC_MD5 + "  good.txt\n", StandardCharsets.UTF_8);
    harness.application.execute(CommandId.VERIFY_CHECKSUMS);
    FakeView.Checksums shown = harness.view.checksums;
    assertEquals(UiText.CHECKSUM_NO_LIST.text(), shown.status);
    assertEquals(UiText.BUTTON_CHOOSE_LIST.text(), shown.chooseLabel);
    shown.status = null;
    shown.input.choose();
    harness.view.openChooser.accept(List.of(list));
    finished();
    assertEquals(List.of("good.txt: OK"), shown.lines);
    assertEquals(UiText.CHECKSUM_VERIFY_DONE.format(1, 0, 0, 0, 0), shown.status);
  }

  /** Windows tools write a UTF-8 list with a byte order mark; the first line still verifies. */
  @Test
  void verifyReadsAChosenListWithAByteOrderMark() throws Exception {
    Path folder = Files.createDirectory(temp.resolve("lists"));
    Files.writeString(folder.resolve("good.txt"), "abc", StandardCharsets.US_ASCII);
    Path list = folder.resolve("SHA256SUMS");
    Files.writeString(list, "\uFEFF" + ABC_SHA256 + "  good.txt\n", StandardCharsets.UTF_8);
    harness.application.execute(CommandId.VERIFY_CHECKSUMS);
    FakeView.Checksums shown = harness.view.checksums;
    shown.status = null;
    shown.input.choose();
    harness.view.openChooser.accept(List.of(list));
    finished();
    assertEquals(List.of("good.txt: OK"), shown.lines);
    assertEquals(UiText.CHECKSUM_VERIFY_DONE.format(1, 0, 0, 0, 0), shown.status);
  }

  /** The editor keeps CR-only breaks, so the document's text ends each list line at CR. */
  @Test
  void verifyReadsADocumentListWithCrBreaks() throws Exception {
    Files.writeString(temp.resolve("a.txt"), "abc", StandardCharsets.US_ASCII);
    Files.writeString(temp.resolve("b.txt"), "abc", StandardCharsets.US_ASCII);
    Path list = temp.resolve("SHA256SUMS");
    Files.writeString(
        list, ABC_SHA256 + "  a.txt\r" + ABC_SHA256 + "  b.txt\r", StandardCharsets.US_ASCII);
    harness.open(list);
    harness.application.execute(CommandId.VERIFY_CHECKSUMS);
    FakeView.Checksums shown = finished();
    assertEquals(List.of("a.txt: OK", "b.txt: OK"), shown.lines);
  }

  /**
   * Two selections of a UTF-16LE document with CRLF breaks: the bytes are the selections joined by
   * CRLF, encoded without the byte order mark.
   */
  @Test
  void selectionHashesTheSavedBytesInTheDocumentsEncoding() throws Exception {
    Charset utf16 = StandardCharsets.UTF_16LE;
    byte[] text = "ab\r\ncd\r\nef".getBytes(utf16);
    byte[] bytes = new byte[text.length + 2];
    bytes[0] = (byte) 0xff;
    bytes[1] = (byte) 0xfe;
    System.arraycopy(text, 0, bytes, 2, text.length);
    Path file = temp.resolve("u.txt");
    Files.write(file, bytes);
    harness.open(file);
    EditorController editor = harness.application.documents().activeEditor();
    CommandStates states = new CommandStates(harness.application);
    assertFalse(states.enabled(CommandId.SHA256_SELECTION));
    editor.selections().set(SelectionSet.of(List.of(Selection.of(0, 6), Selection.of(8, 10)), 0));
    assertTrue(states.enabled(CommandId.SHA256_SELECTION));
    harness.application.execute(CommandId.SHA256_SELECTION);
    final String expected = sha256("ab\r\ncd\r\nef".getBytes(utf16));
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return expected.equals(harness.view.clipboard);
          }
        },
        "copied");
    assertEquals(
        UiText.CHECKSUM_SELECTION_COPIED.format("SHA-256", "UTF-16LE", "CRLF", expected),
        harness.view.notices.get(harness.view.notices.size() - 1));
  }

  @Test
  void aSelectionTheCharsetCannotEncodeCopiesNothing() throws Exception {
    Path file = temp.resolve("latin.txt");
    Files.write(file, "café".getBytes(StandardCharsets.ISO_8859_1));
    harness.open(file);
    EditorController editor = harness.application.documents().activeEditor();
    String charset = editor.document().encoding().charset().name();
    editor.selections().set(SelectionSet.of(List.of(Selection.of(0, 4)), 0));
    editor.type("中");
    editor.selections().set(SelectionSet.of(List.of(Selection.of(0, 1)), 0));
    harness.view.clipboard = null;
    harness.application.execute(CommandId.MD5_SELECTION);
    final String notice = UiText.CHECKSUM_SELECTION_UNENCODABLE.format(charset);
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return harness.view.notices.contains(notice);
          }
        },
        "refused");
    assertNull(harness.view.clipboard);
  }

  /** x-JISAutoDetect decodes but has no encoder: the command says so instead of hashing. */
  @Test
  void aSelectionInACharsetWithNoEncoderCopiesNothing() throws Exception {
    final Charset auto = Charset.forName("x-JISAutoDetect");
    assumeFalse(auto.canEncode());
    harness.open(Files.writeString(temp.resolve("a.txt"), "one\n"));
    harness.application.documents().reopenWithEncoding(auto);
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return harness
                .application
                .documents()
                .activeDocument()
                .encoding()
                .charset()
                .equals(auto);
          }
        },
        "reopened");
    harness.awaitIdle("reopen");
    EditorController editor = harness.application.documents().activeEditor();
    editor.selections().set(SelectionSet.of(List.of(Selection.of(0, 3)), 0));
    harness.view.clipboard = null;
    harness.application.execute(CommandId.SHA256_SELECTION);
    harness.awaitIdle("hash");
    assertEquals(
        UiText.CHECKSUM_SELECTION_NO_ENCODER.format(auto.name()),
        harness.view.notices.get(harness.view.notices.size() - 1));
    assertNull(harness.view.clipboard);
  }

  @Test
  void anAlgorithmNoProviderOffersIsLeftOut() {
    assertEquals(
        EnumSet.of(ChecksumAlgorithm.SHA_256, ChecksumAlgorithm.SHA_512),
        ChecksumTools.probe(
            new ChecksumTools.DigestSource() {
              @Override
              public MessageDigest create(ChecksumAlgorithm algorithm)
                  throws NoSuchAlgorithmException {
                if (!algorithm.collisionResistant()) {
                  throw new NoSuchAlgorithmException(algorithm.javaName());
                }
                return algorithm.newDigest();
              }
            }));
    CommandStates states = new CommandStates(harness.application);
    for (CommandId command : CommandId.values()) {
      assertNull(states.unavailable(command), command.name());
    }
    assertTrue(states.enabled(CommandId.MD5_TEXT));
    assertTrue(states.enabled(CommandId.VERIFY_CHECKSUMS));
  }
}
