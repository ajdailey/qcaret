package io.github.ajdailey.qcaret.app.controller;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.session.Session;
import io.github.ajdailey.qcaret.app.view.ManualUiDispatcher;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.document.Document;
import io.github.ajdailey.qcaret.core.document.LoadOptions;
import io.github.ajdailey.qcaret.core.testkit.Poll;
import io.github.ajdailey.qcaret.core.text.LineEnding;
import io.github.ajdailey.qcaret.core.text.TextEncoding;
import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Per-file settings from EditorConfig and modelines through open, save, Save As and reopen. */
class FileSettingsControllerTest {
  private static final Duration BOUND = Duration.ofSeconds(20);
  private static final Charset CP1252 = Charset.forName("windows-1252");

  @TempDir Path temp;

  private final ManualUiDispatcher ui = new ManualUiDispatcher();
  private final FakeView view = new FakeView(ui);
  private final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
  private Workers workers;
  private DocumentController documents;

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
    documents.setFileSettings(true, true);
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
    assertEquals(null, failure.get());
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

  private void save() throws Exception {
    documents.save();
    idle();
  }

  /** Writes a root {@code .editorconfig} in a directory. */
  private static Path config(Path dir, String sections) throws Exception {
    Files.createDirectories(dir);
    return Files.writeString(dir.resolve(".editorconfig"), "root = true\n" + sections);
  }

  @Test
  void editorConfigSetsIndentationAndTheToolTipNamesIt() throws Exception {
    Path project = temp.resolve("p");
    Path ec = config(project, "[*.c]\nindent_style = space\nindent_size = 2\n");
    Document d = open(Files.writeString(project.resolve("a.c"), "int x;\n"));
    assertEquals(2, d.tabWidth());
    assertTrue(d.insertSpaces());
    String tip = view.tabs.get(0).toolTip;
    assertTrue(tip.contains(ec.toString()), tip);

    Document plain = open(Files.writeString(project.resolve("a.txt"), "x\n"));
    assertEquals(Document.DEFAULT_TAB_WIDTH, plain.tabWidth());
    assertEquals(project.resolve("a.txt").toString(), view.tabs.get(1).toolTip);
  }

  /** Rows: what each source declares, then the width the document takes. */
  @Test
  void precedenceModelineThenEditorConfigThenRememberedThenPreferences() throws Exception {
    documents.setTabDefaults(5, false);
    final Path dir = temp.resolve("q");
    Files.createDirectories(dir);
    documents.setFileMemory(
        new DocumentController.FileMemory() {
          @Override
          public Session.FileState state(Path path) {
            return path.getFileName().toString().startsWith("r")
                ? new Session.FileState(path.toString(), "", "", 7, false, "", 0)
                : null;
          }

          @Override
          public void remember(Session.FileState state) {
            // Not kept.
          }
        });
    config(dir, "[e*]\ntab_width = 3\n[re*]\ntab_width = 3\n");
    Object[][] rows = {
      {"plain.txt", "x\n", 5},
      {"r.txt", "x\n", 7},
      {"e.txt", "x\n", 3},
      {"re.txt", "x\n", 3},
      {"em.txt", "x\n# vim: ts=6\n", 6},
      {"rem.txt", "# -*- tab-width: 2 -*-\nx\n", 2},
    };
    for (Object[] row : rows) {
      Document d = open(Files.writeString(dir.resolve((String) row[0]), (String) row[1]));
      assertEquals(row[2], d.tabWidth(), (String) row[0]);
    }
  }

  @Test
  void saveTrimsAndEndsTheTextAsOneUndoStep() throws Exception {
    Path dir = temp.resolve("t");
    config(dir, "[*]\ntrim_trailing_whitespace = true\ninsert_final_newline = true\n");
    Path file = Files.writeString(dir.resolve("a.txt"), "one  \r\ntwo\t\r\nthree ");
    Document d = open(file);
    documents.activeEditor().type("x");
    save();
    assertEquals("xone\r\ntwo\r\nthree\r\n", Files.readString(file));
    assertFalse(d.isDirty());
    documents.activeEditor().undo();
    assertEquals("xone  \r\ntwo\t\r\nthree ", d.text().toString());
  }

  @Test
  void falseAndMissingPropertiesLeaveTheTextAlone() throws Exception {
    Path dir = temp.resolve("f");
    config(dir, "[*]\ntrim_trailing_whitespace = false\ninsert_final_newline = false\n");
    Path file = Files.writeString(dir.resolve("a.txt"), "one  \ntwo ");
    open(file);
    documents.activeEditor().type("x");
    save();
    assertEquals("xone  \ntwo ", Files.readString(file));
  }

  /** A file over the 1 MiB load block, forced to windows-1252, trimmed and saved. */
  @Test
  void aLargeFileWithAForcedCharsetLoadsTrimsAndSaves() throws Exception {
    Path dir = temp.resolve("big");
    config(dir, "[*]\ntrim_trailing_whitespace = true\ninsert_final_newline = true\n");
    ByteArrayOutputStream raw = new ByteArrayOutputStream();
    ByteArrayOutputStream expected = new ByteArrayOutputStream();
    byte[] line = "café au lait \t\n".getBytes(CP1252);
    byte[] trimmed = "café au lait\n".getBytes(CP1252);
    while (raw.size() < 3 * (1 << 20) + 5) {
      raw.writeBytes(line);
      expected.writeBytes(trimmed);
    }
    raw.writeBytes("end  ".getBytes(CP1252));
    expected.writeBytes("end\n".getBytes(CP1252));
    Path file = dir.resolve("big.txt");
    Files.write(file, raw.toByteArray());
    open(file);
    documents.reopenWithEncoding(CP1252);
    idle();
    Document d = documents.activeDocument();
    assertEquals(CP1252, d.encoding().charset());
    documents.activeEditor().type("x");
    save();
    byte[] written = Files.readAllBytes(file);
    assertEquals('x', written[0]);
    assertArrayEquals(expected.toByteArray(), Arrays.copyOfRange(written, 1, written.length));
  }

  /** Rows: the file's text, then Enter's ending; end_of_line = lf converts nothing. */
  @Test
  void endOfLineSetsEnterOnlyForATextWithoutBreaks() throws Exception {
    Path dir = temp.resolve("e");
    config(dir, "[*]\nend_of_line = lf\ntrim_trailing_whitespace = true\n");
    Object[][] rows = {
      {"cr.txt", "a \rb\r", LineEnding.CR},
      {"crlf.txt", "a \r\nb\r\n", LineEnding.CRLF},
      {"lf.txt", "a \nb\n", LineEnding.LF},
      {"mixed.txt", "a \r\nb\nc\r\n", LineEnding.CRLF},
      {"none.txt", "a ", LineEnding.LF},
    };
    for (Object[] row : rows) {
      Path file = Files.writeString(dir.resolve((String) row[0]), (String) row[1]);
      Document d = open(file);
      assertEquals(row[2], d.lineEnding(), (String) row[0]);
      documents.activeEditor().type("x");
      save();
      assertEquals(
          "x" + ((String) row[1]).replace(" ", ""),
          Files.readString(file),
          row[0] + " " + view.infos + view.notices + d.text().toString().replace("\r", "CR"));
    }
    Files.writeString(dir.resolve(".editorconfig"), "root = true\n[*]\nend_of_line = crlf\n");
    assertEquals(LineEnding.CRLF, open(Files.writeString(dir.resolve("n.txt"), "")).lineEnding());
  }

  @Test
  void aCharsetDeclarationLosesToABomAndDecidesANewFile() throws Exception {
    Path dir = temp.resolve("c");
    config(dir, "[*]\ncharset = latin1\n[n*]\ncharset = utf-16le\nend_of_line = crlf\n");
    Path bom = dir.resolve("bom.txt");
    Files.write(bom, new byte[] {(byte) 0xef, (byte) 0xbb, (byte) 0xbf, (byte) 0xc3, (byte) 0xa9});
    Document d = open(bom);
    assertEquals(TextEncoding.of(StandardCharsets.UTF_8, true), d.encoding());
    assertEquals("é", d.text().toString());

    Path latin = dir.resolve("l.txt");
    Files.write(latin, new byte[] {(byte) 0xc3, (byte) 0xa9});
    assertEquals("Ã©", open(latin).text().toString());

    final Path created = dir.resolve("new.txt");
    final int before = view.tabs.size();
    documents.open(List.of(created));
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            Document now = view.tabs.size() > before ? documents.activeDocument() : null;
            return now != null && now.encoding().charset().equals(StandardCharsets.UTF_16LE);
          }
        },
        "the new file's charset");
    Document fresh = documents.activeDocument();
    assertEquals(LineEnding.CRLF, fresh.lineEnding());
    assertFalse(fresh.isDirty());
    documents.activeEditor().type("a");
    documents.activeEditor().newline();
    save();
    assertArrayEquals(
        new byte[] {(byte) 0xff, (byte) 0xfe, 'a', 0, '\r', 0, '\n', 0},
        Files.readAllBytes(created));
  }

  @Test
  void saveAsElsewhereAndReopenReadTheSettingsAgain() throws Exception {
    Path one = temp.resolve("one");
    Path two = temp.resolve("two");
    config(one, "[*]\nindent_size = 3\n");
    Path ec = config(two, "[*]\nindent_size = 6\ntrim_trailing_whitespace = true\n");
    Document d = open(Files.writeString(one.resolve("a.txt"), "a \n"));
    assertEquals(3, d.tabWidth());
    documents.saveAs();
    final Path copy = two.resolve("a.txt");
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
    assertEquals(6, d.tabWidth());
    assertEquals("a\n", Files.readString(copy));
    assertTrue(view.selected.toolTip.contains(ec.toString()), view.selected.toolTip);

    Files.writeString(ec, "root = true\n[*]\nindent_size = 4\n");
    documents.reopenWithEncoding(StandardCharsets.UTF_8);
    idle();
    assertEquals(4, documents.activeDocument().tabWidth());
  }

  @Test
  void theSwitchesTurnEachSourceOff() throws Exception {
    Path dir = temp.resolve("s");
    config(dir, "[*]\nindent_size = 3\n");
    documents.setFileSettings(false, true);
    assertEquals(
        Document.DEFAULT_TAB_WIDTH,
        open(Files.writeString(dir.resolve("a.txt"), "x\n")).tabWidth());
    assertEquals(2, open(Files.writeString(dir.resolve("b.txt"), "# vim: ts=2\n")).tabWidth());
    documents.setFileSettings(true, false);
    assertEquals(3, open(Files.writeString(dir.resolve("c.txt"), "# vim: ts=2\n")).tabWidth());
  }

  @Test
  void anEditDuringTheCleanupComputesItAgainAndEscStopsTheSave() throws Exception {
    Path dir = temp.resolve("r");
    config(dir, "[*]\ntrim_trailing_whitespace = true\n");
    Path file = Files.writeString(dir.resolve("a.txt"), "a  \n");
    Document d = open(file);
    documents.save();
    documents.activeEditor().type("y");
    idle();
    assertEquals("ya\n", Files.readString(file));
    assertFalse(d.isDirty());

    documents.activeEditor().type("z ");
    documents.save();
    documents.cancelSave();
    idle();
    assertEquals("ya\n", Files.readString(file));
    assertEquals("yz a\n", d.text().toString());
  }
}
