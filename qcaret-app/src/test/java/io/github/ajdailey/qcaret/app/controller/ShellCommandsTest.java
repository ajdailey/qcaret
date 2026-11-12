package io.github.ajdailey.qcaret.app.controller;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.config.Options;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.session.Session;
import io.github.ajdailey.qcaret.app.view.UnsavedChoice;
import io.github.ajdailey.qcaret.core.document.Document;
import io.github.ajdailey.qcaret.core.text.LineEnding;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The application-shell commands over a fake view: the binary file view, the encoding and
 * line-ending commands, the tab commands and the Help commands.
 */
class ShellCommandsTest {
  @TempDir Path temp;

  /** A PNG signature and header start: NUL bytes that no UTF-16 or UTF-32 pattern explains. */
  static final byte[] BINARY = {
    (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n', 0, 0, 0, 13, 'I', 'H', 'D', 'R', 0, 0, 1, 0
  };

  private Harness harness;

  @BeforeEach
  void create() {
    harness = new Harness(temp);
  }

  @AfterEach
  void close() throws InterruptedException {
    harness.application.documents().dispose();
    harness.close();
  }

  private DocumentController documents() {
    return harness.application.documents();
  }

  private Document active() {
    return documents().activeDocument();
  }

  private static List<String> listing(Path dir) throws Exception {
    List<String> names = new ArrayList<String>();
    try (DirectoryStream<Path> files = Files.newDirectoryStream(dir)) {
      for (Path path : files) {
        names.add(path.getFileName().toString());
      }
    }
    names.sort(null);
    return names;
  }

  @Test
  void aBinaryFileOpensReadOnlyAfterThePromptAndNoCommandWritesIt() throws Exception {
    Path dir = Files.createDirectory(temp.resolve("bin"));
    byte[] bytes = new byte[600];
    for (int i = 0; i < bytes.length; i++) {
      bytes[i] = (byte) (i * 7);
    }
    Path file = Files.write(dir.resolve("blob.bin"), bytes);
    documents().open(List.of(file));
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return harness.view.openBinary != null;
          }
        },
        "binary prompt");
    assertEquals(0, documents().count());
    assertEquals("blob.bin", harness.view.binaryName);
    harness.view.openBinary.run();
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return documents().count() == 1;
          }
        },
        "binary view");
    harness.awaitIdle("binary open");
    assertTrue(documents().activeBinary());
    assertTrue(active().isReadOnly());
    assertEquals(StandardCharsets.ISO_8859_1, active().encoding().charset());
    assertEquals(UiText.TAB_BINARY.format("blob.bin"), harness.view.selected.title);
    CommandStates states = new CommandStates(harness.application);
    assertFalse(states.enabled(CommandId.SAVE));
    assertFalse(states.enabled(CommandId.SAVE_AS));
    assertFalse(states.enabled(CommandId.PASTE));
    assertFalse(states.saveEncodingEnabled());
    assertFalse(states.reopenEnabled());
    assertEquals(UiText.STATUS_BINARY.text(), states.status().readOnly());
    String before = active().text().toString();
    harness.view.clipboard = "pasted";
    Path target = dir.resolve("copy.bin");
    // Every command but those that open, close or switch documents.
    EnumSet<CommandId> skipped =
        EnumSet.of(
            CommandId.QUIT,
            CommandId.NEW,
            CommandId.OPEN,
            CommandId.CLOSE,
            CommandId.CLOSE_ALL,
            CommandId.CLOSE_OTHERS);
    for (CommandId command : CommandId.values()) {
      if (skipped.contains(command)) {
        continue;
      }
      harness.application.execute(command);
      if (harness.view.saveChooser != null) {
        Consumer<Path> chosen = harness.view.saveChooser;
        harness.view.saveChooser = null;
        chosen.accept(target);
      }
      harness.awaitIdle(command.name());
    }
    harness.application.saveWithEncoding(StandardCharsets.UTF_8);
    harness.application.reopenWithEncoding(StandardCharsets.UTF_8);
    harness.application.execute(CommandId.SAVE);
    harness.application.execute(CommandId.SAVE_AS);
    harness.awaitIdle("save");
    documents().activeEditor().type("x");
    documents().activeEditor().replaceFromView(0, 1, "y");
    assertEquals(before, active().text().toString());
    assertFalse(active().isDirty());
    assertNull(harness.view.saveChooser);
    assertArrayEquals(bytes, Files.readAllBytes(file));
    assertEquals(List.of("blob.bin"), listing(dir));
    assertTrue(
        harness.view.infos.contains(
            UiText.SAVE_TITLE.text() + ": " + UiText.SAVE_BINARY.format("blob.bin")),
        harness.view.infos.toString());
  }

  @Test
  void aDeclinedBinaryPromptOpensNothing() throws Exception {
    Path file = Files.write(temp.resolve("zeros.bin"), BINARY);
    documents().open(List.of(file));
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return harness.view.openBinary != null;
          }
        },
        "binary prompt");
    harness.awaitIdle("binary prompt");
    assertEquals(0, documents().count());
  }

  @Test
  void reopenWithEncodingReplacesTheDocumentInItsTab() throws Exception {
    Path first = Files.writeString(temp.resolve("first.txt"), "one");
    Path latin = temp.resolve("latin.txt");
    Files.write(latin, "café".getBytes(StandardCharsets.UTF_8));
    Path last = Files.writeString(temp.resolve("last.txt"), "three");
    harness.open(first);
    harness.open(latin);
    harness.open(last);
    documents().select(1);
    assertEquals("café", active().text().toString());
    assertTrue(new CommandStates(harness.application).reopenEnabled());
    harness.application.reopenWithEncoding(StandardCharsets.ISO_8859_1);
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return active() != null
                && active().encoding().charset().equals(StandardCharsets.ISO_8859_1);
          }
        },
        "reopened");
    harness.awaitIdle("reopen");
    assertEquals(3, documents().count());
    assertEquals(1, documents().activeIndex());
    assertEquals("cafÃ©", active().text().toString());
    assertEquals("latin.txt", harness.view.tabs.get(1).title);
  }

  @Test
  void saveWithEncodingWritesTheNewCharset() throws Exception {
    Path file = Files.writeString(temp.resolve("e.txt"), "été");
    harness.open(file);
    harness.application.saveWithEncoding(StandardCharsets.UTF_16LE);
    assertTrue(active().isDirty());
    assertEquals("UTF-16LE", new CommandStates(harness.application).saveEncoding());
    assertFalse(new CommandStates(harness.application).reopenEnabled());
    harness.application.execute(CommandId.SAVE);
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !active().isDirty();
          }
        },
        "saved");
    harness.awaitIdle("save");
    assertArrayEquals("été".getBytes(StandardCharsets.UTF_16LE), Files.readAllBytes(file));
  }

  @Test
  void lineEndingConversionIsOneUndoStepAndSetsTheEnding() throws Exception {
    Path file = Files.writeString(temp.resolve("mixed.txt"), "a\r\nb\nc\rd");
    harness.open(file);
    CommandStates states = new CommandStates(harness.application);
    assertEquals(UiText.STATUS_MIXED.text(), states.status().lineEnding());
    assertFalse(states.checked(CommandId.LINE_ENDING_LF));
    harness.application.execute(CommandId.LINE_ENDING_LF);
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return active().text().toString().equals("a\nb\nc\nd");
          }
        },
        "converted");
    harness.awaitIdle("conversion");
    assertEquals(List.of(true, false), harness.view.selected.shown.busy);
    assertEquals(LineEnding.LF, active().lineEnding());
    assertTrue(states.checked(CommandId.LINE_ENDING_LF));
    assertEquals(UiText.STATUS_LF.text(), states.status().lineEnding());
    harness.application.execute(CommandId.UNDO);
    assertEquals("a\r\nb\nc\rd", active().text().toString());
    harness.application.execute(CommandId.LINE_ENDING_CRLF);
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return active().text().toString().equals("a\r\nb\r\nc\r\nd");
          }
        },
        "converted to CR LF");
    harness.awaitIdle("conversion");
  }

  @Test
  void aConversionIsDroppedWhenTheDocumentChangedMeanwhile() throws Exception {
    Path file = Files.writeString(temp.resolve("lf.txt"), "a\nb\n");
    harness.open(file);
    harness.application.execute(CommandId.LINE_ENDING_CRLF);
    documents().activeEditor().type("z");
    harness.awaitIdle("conversion");
    assertEquals("za\nb\n", active().text().toString());
  }

  @Test
  void tabCommandsCycleAndCloseOthers() throws Exception {
    harness.open(Files.writeString(temp.resolve("a.txt"), "a"));
    harness.open(Files.writeString(temp.resolve("b.txt"), "b"));
    harness.open(Files.writeString(temp.resolve("c.txt"), "c"));
    assertEquals(2, documents().activeIndex());
    harness.application.execute(CommandId.NEXT_TAB);
    assertEquals(0, documents().activeIndex());
    harness.application.execute(CommandId.PREVIOUS_TAB);
    assertEquals(2, documents().activeIndex());
    List<CommandStates.OpenedDocument> opened =
        new CommandStates(harness.application).openedDocuments();
    assertEquals(3, opened.size());
    assertTrue(opened.get(2).shown());
    harness.view.tabs.get(1).input.copyPathRequested(true);
    assertEquals("b.txt", harness.view.clipboard);
    documents().activeEditor().type("dirty");
    harness.view.tabs.get(1).input.closeOthersRequested();
    assertNotNull(harness.view.unsaved);
    harness.view.unsaved.accept(UnsavedChoice.DISCARD);
    assertEquals(1, documents().count());
    assertEquals("b", active().text().toString());
    CommandStates.TabItems items =
        new CommandStates(harness.application).tabItems(harness.view.tabs.get(0).input);
    assertFalse(items.closeOthers());
    assertTrue(items.file());
  }

  @Test
  void closeSideClosesInTabOrderAndCancelStops() throws Exception {
    harness.open(Files.writeString(temp.resolve("a.txt"), "a"));
    harness.open(Files.writeString(temp.resolve("b.txt"), "b"));
    harness.open(Files.writeString(temp.resolve("c.txt"), "c"));
    harness.open(Files.writeString(temp.resolve("d.txt"), "d"));
    documents().select(1);
    documents().activeEditor().type("dirty");
    documents().select(3);
    CommandStates states = new CommandStates(harness.application);
    CommandStates.TabItems middle = states.tabItems(harness.view.tabs.get(2).input);
    assertTrue(middle.closeLeft());
    assertTrue(middle.closeRight());
    harness.view.tabs.get(2).input.closeSideRequested(true);
    assertEquals(3, documents().count());
    assertEquals("dirtyb", active().text().toString());
    assertNotNull(harness.view.unsaved);
    harness.view.unsaved.accept(UnsavedChoice.CANCEL);
    assertEquals(3, documents().count());
    harness.view.tabs.get(1).input.closeSideRequested(true);
    harness.view.unsaved.accept(UnsavedChoice.DISCARD);
    assertEquals(2, documents().count());
    assertEquals("c", active().text().toString());
    CommandStates.TabItems first = states.tabItems(harness.view.tabs.get(0).input);
    assertFalse(first.closeLeft());
    assertTrue(first.closeRight());
    assertFalse(states.tabItems(harness.view.tabs.get(1).input).closeRight());
  }

  @Test
  void closeSideKeepsASelectionThatStaysOpen() throws Exception {
    harness.open(Files.writeString(temp.resolve("a.txt"), "a"));
    harness.open(Files.writeString(temp.resolve("b.txt"), "b"));
    harness.open(Files.writeString(temp.resolve("c.txt"), "c"));
    harness.view.tabs.get(1).input.closeSideRequested(true);
    assertEquals(2, documents().count());
    assertEquals("c", active().text().toString());
  }

  /** File > Close Multiple Tabs acts on the selected tab, as the tab menu does on its own. */
  @Test
  void closeLeftAndRightCommandsActOnTheSelectedTab() throws Exception {
    harness.open(Files.writeString(temp.resolve("a.txt"), "a"));
    harness.open(Files.writeString(temp.resolve("b.txt"), "b"));
    harness.open(Files.writeString(temp.resolve("c.txt"), "c"));
    harness.open(Files.writeString(temp.resolve("d.txt"), "d"));
    documents().select(1);
    CommandStates states = new CommandStates(harness.application);
    assertTrue(states.enabled(CommandId.CLOSE_LEFT));
    harness.application.execute(CommandId.CLOSE_RIGHT);
    assertEquals(2, documents().count());
    assertEquals("b", active().text().toString());
    assertFalse(states.enabled(CommandId.CLOSE_RIGHT));
    harness.application.execute(CommandId.CLOSE_LEFT);
    assertEquals(1, documents().count());
    assertEquals("b", active().text().toString());
    assertFalse(states.enabled(CommandId.CLOSE_LEFT));
  }

  @Test
  void openContainingFolderLaunchesTheFoldersThatExist() throws Exception {
    Path folder = Files.createDirectory(temp.resolve("held"));
    Path file = Files.writeString(folder.resolve("a.txt"), "a");
    harness.open(file);
    harness.application.openContainingFolder(harness.view.tabs.get(0).input);
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return harness.view.launched.size() == 1;
          }
        },
        "launched");
    assertEquals(folder.toRealPath(), Path.of(harness.view.launched.get(0)).toRealPath());
    Files.delete(file);
    Files.delete(folder);
    harness.application.openContainingFolder(harness.view.tabs.get(0).input);
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !harness.view.notices.isEmpty();
          }
        },
        "notice");
    assertEquals(1, harness.view.launched.size());
    assertFalse(Files.exists(folder));
  }

  @Test
  void theToolbarChoiceIsASettingEveryWindowApplies() throws Exception {
    CommandStates states = new CommandStates(harness.application);
    assertTrue(states.checked(CommandId.TOOLBAR));
    assertTrue(states.enabled(CommandId.TOOLBAR));
    harness.application.execute(CommandId.TOOLBAR);
    assertFalse(states.checked(CommandId.TOOLBAR));
    assertEquals(Boolean.FALSE, harness.view.toolbarShown);
    assertFalse(harness.application.preferences().state().toolbar());
    harness.application.execute(CommandId.TOOLBAR);
    assertEquals(Boolean.TRUE, harness.view.toolbarShown);
  }

  @Test
  void lineNumbersToggleAndRestoreWithTheDisplayState() throws Exception {
    CommandStates states = new CommandStates(harness.application);
    assertTrue(states.checked(CommandId.LINE_NUMBERS));
    assertTrue(states.enabled(CommandId.LINE_NUMBERS));
    harness.application.execute(CommandId.LINE_NUMBERS);
    assertFalse(states.checked(CommandId.LINE_NUMBERS));
    assertEquals(Boolean.FALSE, harness.view.lineNumbersShown);
    assertFalse(documents().sessionDisplay(false).lineNumbers());
    documents().restoreDisplay(Session.Display.DEFAULTS);
    assertTrue(states.checked(CommandId.LINE_NUMBERS));
    assertEquals(Boolean.TRUE, harness.view.lineNumbersShown);
  }

  @Test
  void showLogClearsTheWarningMarker() throws Exception {
    assertFalse(harness.view.warningMarker);
    harness.recent.append("WARN mock warning", true);
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return harness.view.warningMarker;
          }
        },
        "marker");
    harness.application.execute(CommandId.SHOW_LOG);
    assertTrue(harness.view.log.contains("mock warning"), harness.view.log);
    assertFalse(harness.view.warningMarker);
  }

  @Test
  void debugLogFileFollowsTheFileAndIsSaved() throws Exception {
    CommandStates states = new CommandStates(harness.application);
    assertFalse(states.checked(CommandId.DEBUG_LOG_FILE));
    harness.application.execute(CommandId.DEBUG_LOG_FILE);
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !harness.view.notices.isEmpty();
          }
        },
        "notice");
    assertTrue(states.checked(CommandId.DEBUG_LOG_FILE));
    assertTrue(harness.application.preferences().debugLogFile());
    harness.files.fail = true;
    harness.application.execute(CommandId.DEBUG_LOG_FILE);
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return harness.view.notices.size() == 2;
          }
        },
        "closed notice");
    assertFalse(states.checked(CommandId.DEBUG_LOG_FILE));
    harness.application.execute(CommandId.DEBUG_LOG_FILE);
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return harness.view.notices.size() == 3;
          }
        },
        "notices");
    assertFalse(states.checked(CommandId.DEBUG_LOG_FILE));
    assertTrue(harness.view.notices.get(2).contains("mock failure"), harness.view.notices.get(2));
  }

  @Test
  void theUserGuideIsCopiedAndOpenedAndReportAnIssueOpensOnlyTheIssuePage() throws Exception {
    harness.application.execute(CommandId.USER_GUIDE);
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return harness.view.launched.size() == 1;
          }
        },
        "guide");
    harness.application.execute(CommandId.REPORT_ISSUE);
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return harness.view.launched.size() == 2;
          }
        },
        "issue page");
    Path guide = Path.of(harness.view.launched.get(0));
    assertTrue(Files.readString(guide).contains("QCaret User Guide"));
    assertEquals(
        List.of(guide.toString(), "https://github.com/ajdailey/qcaret/issues"),
        harness.view.launched);
    assertTrue(harness.view.clipboard.contains("Java"), harness.view.clipboard);
  }

  @Test
  void zoomIsBounded() {
    for (int i = 0; i < 80; i++) {
      harness.application.execute(CommandId.ZOOM_IN);
    }
    assertEquals(Options.MAX_FONT_SIZE, harness.view.fontSize);
    assertFalse(new CommandStates(harness.application).enabled(CommandId.ZOOM_IN));
    harness.application.execute(CommandId.ZOOM_RESET);
    assertEquals(0, harness.view.fontSize);
  }
}
