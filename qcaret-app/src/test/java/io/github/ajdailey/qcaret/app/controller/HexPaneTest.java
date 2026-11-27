package io.github.ajdailey.qcaret.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.config.MarkdownOpenMode;
import io.github.ajdailey.qcaret.app.config.Preferences;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.session.Session;
import io.github.ajdailey.qcaret.app.view.HexCopy;
import io.github.ajdailey.qcaret.app.view.HexKey;
import io.github.ajdailey.qcaret.app.view.HexMode;
import io.github.ajdailey.qcaret.app.view.HexSelection;
import io.github.ajdailey.qcaret.app.view.MarkdownViewMode;
import io.github.ajdailey.qcaret.core.document.Selection;
import io.github.ajdailey.qcaret.core.document.SelectionSet;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
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
 * A tab's hex pane through the application controller: View > Hex View modes and their cycle, the
 * mode kept per file and in the session, caret sync with the editor over UTF-8 multi-byte chars, an
 * edit dimming the rows until the rebuild, the commands a focused pane takes, the bytes-per-row
 * preference, the exclusion with the Markdown preview and the round-trip notice.
 */
class HexPaneTest {
  /** a, e-acute (2 bytes), euro (3), an emoji pair (4), z, LF: 12 bytes. */
  private static final String TEXT = "aé€😀z\n";

  @TempDir Path temp;

  private Harness harness;
  private CommandStates states;
  private DocumentController documents;
  private final List<Session.FileState> remembered = new ArrayList<Session.FileState>();

  @BeforeEach
  void create() {
    harness = new Harness(temp);
    states = new CommandStates(harness.application);
    documents = harness.application.documents();
    documents.setFileMemory(
        new DocumentController.FileMemory() {
          @Override
          public Session.FileState state(Path path) {
            for (Session.FileState state : remembered) {
              if (state.path().equals(path.toString())) {
                return state;
              }
            }
            return null;
          }

          @Override
          public void remember(Session.FileState state) {
            remembered.add(state);
          }
        });
  }

  @AfterEach
  void close() throws InterruptedException {
    documents.dispose();
    harness.close();
  }

  private Path open(String name, String text) throws Exception {
    Path file = Files.writeString(temp.resolve(name), text, StandardCharsets.UTF_8);
    harness.open(file);
    return file;
  }

  private FakeHexView pane() {
    return harness.view.hexPanes.get(harness.view.hexPanes.size() - 1);
  }

  /** Runs a mode command and waits for the pane to show bytes of the current revision. */
  private FakeHexView show(CommandId command, final long size) throws InterruptedException {
    harness.application.execute(command);
    final FakeHexView pane = pane();
    awaitBuilt(pane, size);
    return pane;
  }

  private void awaitBuilt(final FakeHexView pane, final long size) throws InterruptedException {
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return pane.rows != null
                && pane.rows.size() == size
                && !pane.dimmed
                && harness.view.active.get() == 0;
          }
        },
        "the pane's bytes");
    harness.ui.runPending();
  }

  /** Waits until the pane's busy cursor, which copy and find raise, is idle. */
  private void awaitPane(final FakeHexView pane, String what) throws InterruptedException {
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return pane.active.get() == 0;
          }
        },
        what);
    harness.ui.runPending();
  }

  private void select(int anchor, int caret) {
    documents
        .activeEditor()
        .selections()
        .set(SelectionSet.of(List.of(Selection.of(anchor, caret)), 0));
  }

  private Selection editorSelection() {
    return documents.activeEditor().selections().selections().primary();
  }

  @Test
  void splitShowsTheEncodedBytesAndSyncsTheCaretBothWays() throws Exception {
    open("a.txt", TEXT);
    assertTrue(states.checked(CommandId.HEX_TEXT));
    FakeHexView pane = show(CommandId.HEX_SPLIT, 12);
    assertEquals(HexMode.SPLIT, pane.mode);
    assertTrue(states.checked(CommandId.HEX_SPLIT));
    select(1, 3);
    assertEquals(new HexSelection(1, 6, false), pane.selection);
    pane.input.clicked(7, false, false);
    assertEquals(Selection.of(3, 3), editorSelection());
    pane.input.clicked(2, false, false);
    pane.input.dragged(4);
    assertEquals(new HexSelection(2, 5, false), pane.selection);
    assertEquals(Selection.of(1, 3), editorSelection());
    pane.input.clicked(9, true, false);
    pane.input.dragged(3);
    assertEquals(Selection.of(5, 2), editorSelection());
    pane.input.copy(HexCopy.TEXT);
    awaitPane(pane, "copy");
    assertEquals("€😀", harness.view.clipboard);
    pane.input.key(HexKey.SOURCE_END, false);
    assertEquals(Selection.of(TEXT.length(), TEXT.length()), editorSelection());
  }

  @Test
  void anEditDimsTheRowsUntilTheRebuildLands() throws Exception {
    open("a.txt", TEXT);
    FakeHexView pane = show(CommandId.HEX_SPLIT, 12);
    select(0, 0);
    documents.activeEditor().type("é");
    assertTrue(pane.dimmed);
    awaitBuilt(pane, 14);
    assertEquals(new HexSelection(2, 2, false), pane.selection);
    final FakeHexView shown = pane;
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return shown.input.row(0) != null;
          }
        },
        "the first row");
    assertEquals((byte) 0xc3, pane.input.row(0).bytes()[0]);
    assertEquals((byte) 0x61, pane.input.row(0).bytes()[2]);
  }

  @Test
  void saveWithEncodingShowsTheBytesInTheNewCharset() throws Exception {
    open("a.txt", TEXT);
    FakeHexView pane = show(CommandId.HEX_SPLIT, 12);
    documents.saveWithEncoding(StandardCharsets.UTF_16LE);
    assertTrue(pane.dimmed);
    awaitBuilt(pane, 2L * TEXT.length());
    assertEquals(StandardCharsets.UTF_16LE, documents.activeDocument().encoding().charset());
  }

  @Test
  void hexModeFocusesThePaneAndTheCycleReturnsToText() throws Exception {
    open("a.txt", TEXT);
    FakeHexView pane = show(CommandId.HEX_CYCLE, 12);
    assertEquals(HexMode.SPLIT, documents.hexMode());
    harness.application.execute(CommandId.HEX_CYCLE);
    assertEquals(HexMode.HEX, pane.mode);
    assertEquals(1, pane.focusCalls);
    harness.application.execute(CommandId.HEX_CYCLE);
    assertEquals(HexMode.TEXT, pane.mode);
    assertTrue(states.checked(CommandId.HEX_TEXT));
  }

  @Test
  void theModeIsKeptPerFileAndInTheSession() throws Exception {
    Path file = open("a.txt", TEXT);
    assertEquals("", documents.sessionTabs(false).get(0).hex());
    show(CommandId.HEX_HEX, 12);
    assertEquals("hex", documents.sessionTabs(false).get(0).hex());
    harness.application.execute(CommandId.CLOSE);
    harness.awaitIdle("close");
    assertEquals("hex", remembered.get(0).hex());
    harness.open(file);
    assertEquals(HexMode.HEX, documents.hexMode());
    awaitBuilt(pane(), 12);
    open("b.txt", "b");
    assertEquals(HexMode.TEXT, documents.hexMode());
  }

  @Test
  void aFocusedPaneTakesCopySelectAllGoToLineAndFind() throws Exception {
    open("a.txt", TEXT);
    FakeHexView pane = show(CommandId.HEX_SPLIT, 12);
    pane.input.focusChanged(true);
    pane.input.clicked(0, false, false);
    pane.input.dragged(2);
    assertTrue(states.enabled(CommandId.COPY));
    harness.application.execute(CommandId.COPY);
    awaitPane(pane, "copy");
    assertEquals("61 C3 A9", harness.view.clipboard);
    harness.application.execute(CommandId.GO_TO_LINE);
    assertNotNull(pane.offsetAnswer);
    pane.offsetAnswer.accept("0x3");
    assertEquals(3, pane.selection.caret());
    harness.application.execute(CommandId.FIND);
    assertEquals("", pane.findText);
    pane.input.find("7A", true, true);
    awaitPane(pane, "find");
    assertEquals(new HexSelection(10, 11, false), pane.selection);
    harness.application.execute(CommandId.FIND_PREVIOUS);
    awaitPane(pane, "find previous");
    assertEquals(10, pane.selection.start());
    assertEquals(UiText.HEX_FIND_WRAPPED.text(), pane.message);
    harness.application.execute(CommandId.SELECT_ALL);
    assertEquals(new HexSelection(0, 12, false), pane.selection);
    pane.input.focusChanged(false);
    select(0, 2);
    harness.application.execute(CommandId.COPY);
    assertEquals("aé", harness.view.clipboard);
  }

  @Test
  void thePreferenceSetsTheBytesPerRow() throws Exception {
    open("a.txt", TEXT);
    FakeHexView pane = show(CommandId.HEX_SPLIT, 12);
    assertEquals(16, pane.rows.bytesPerRow());
    Preferences current = harness.application.settings().current();
    harness
        .application
        .settings()
        .set(current.withOptions(current.options().withHexBytesPerRow(8)));
    assertEquals(8, pane.rows.bytesPerRow());
  }

  @Test
  void thePaneAndTheMarkdownPreviewDoNotShowTogether() throws Exception {
    Preferences current = harness.application.settings().current();
    harness
        .application
        .settings()
        .set(current.withOptions(current.options().withMarkdownOpenMode(MarkdownOpenMode.SOURCE)));
    harness.application.loadGrammars();
    final GrammarLibrary library = harness.application.grammars();
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return library.set() != null;
          }
        },
        "grammars read");
    open("notes.md", "# Mock\n");
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return documents.activeMarkdown();
          }
        },
        "Markdown grammar");
    harness.application.execute(CommandId.MARKDOWN_SPLIT);
    harness.awaitIdle("preview");
    assertEquals(MarkdownViewMode.SPLIT, documents.markdownMode());
    show(CommandId.HEX_SPLIT, 7);
    assertEquals(MarkdownViewMode.SOURCE, documents.markdownMode());
    harness.application.execute(CommandId.MARKDOWN_PREVIEW);
    harness.awaitIdle("preview");
    assertEquals(HexMode.TEXT, documents.hexMode());
    assertEquals(HexMode.TEXT, pane().mode);
  }

  @Test
  void bytesThatDoNotRoundTripShowANoticeThatOpensTheViewer() throws Exception {
    Charset windows31j = Charset.forName("windows-31j");
    byte[] declared =
        "<?xml version=\"1.0\" encoding=\"windows-31j\"?>\n".getBytes(StandardCharsets.US_ASCII);
    byte[] bytes = new byte[declared.length + 3];
    System.arraycopy(declared, 0, bytes, 0, declared.length);
    bytes[declared.length] = (byte) 0xed;
    bytes[declared.length + 1] = 0x40;
    bytes[declared.length + 2] = '\n';
    Path file = Files.write(temp.resolve("a.xml"), bytes);
    harness.open(file);
    assertTrue(documents.activeDocument().isReadOnly());
    FakeHexView pane = show(CommandId.HEX_SPLIT, bytes.length);
    assertEquals(UiText.HEX_BYTES_DIFFER.format(windows31j.displayName()), pane.notice);
    assertEquals(UiText.BUTTON_OPEN_IN_HEX_VIEWER.text(), pane.noticeAction);
    assertNull(pane.input.row(99));
    pane.input.noticeAction();
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return harness.view.hexViewers.size() == 1;
          }
        },
        "viewer");
    assertEquals("a.xml - Hex Viewer", harness.view.hexViewers.get(0).title);
    assertFalse(harness.view.hexViewers.get(0).rows.size() == 0);
    harness.awaitIdle("viewer");
  }
}
