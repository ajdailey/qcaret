package io.github.ajdailey.qcaret.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.view.SearchScope;
import io.github.ajdailey.qcaret.app.view.StatusFields;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Records the checked and enabled state of every command with no document, with a text document,
 * and with a binary file's read-only view, over {@code Preferences.DEFAULTS}. Each state is two
 * characters: {@code c} or {@code -} for checked, {@code e} or {@code -} for enabled. A mismatch
 * prints the whole recorded table.
 */
class CommandStatesTest {
  /** Per command: no document, a text document, a binary view. */
  private static final String EXPECTED =
      String.join(
          "\n",
          "NEW -e -e -e",
          "OPEN -e -e -e",
          "SAVE -- -e --",
          "SAVE_AS -- -e --",
          "SAVE_ALL -- -- --",
          "CLOSE -- -e -e",
          "CLOSE_ALL -- -e -e",
          "REOPEN_CLOSED -- -- --",
          "CLEAR_CLOSED_FILES -- -- --",
          "RELOAD -- -e -e",
          "SET_WORKSPACE -e -e -e",
          "QUICK_OPEN -e -e -e",
          "PRINT -- -e -e",
          "CLEAR_RECENT_FILES -- -e -e",
          "QUIT -e -e -e",
          "UNDO -- -- --",
          "REDO -- -- --",
          "CUT -- -- --",
          "COPY -- -- --",
          "PASTE -- -e --",
          "SELECT_ALL -- -e -e",
          "PREFERENCES -e -e -e",
          "DUPLICATE -- -e --",
          "DELETE_LINE -- -e --",
          "MOVE_LINES_UP -- -e --",
          "MOVE_LINES_DOWN -- -e --",
          "JOIN_LINES -- -e --",
          "UPPER_CASE -- -e --",
          "LOWER_CASE -- -e --",
          "INDENT -- -e --",
          "OUTDENT -- -e --",
          "TOGGLE_LINE_COMMENT -- -- --",
          "TOGGLE_BLOCK_COMMENT -- -- --",
          "TAB_WIDTH_2 -- -e -e",
          "TAB_WIDTH_4 -- ce ce",
          "TAB_WIDTH_8 -- -e -e",
          "TAB_WIDTH_OTHER -- -e -e",
          "INDENT_WITH_SPACES -- -e -e",
          "GO_TO_LINE -- -e -e",
          "READ_ONLY -- -e c-",
          "ADD_CARET_ABOVE -- -e -e",
          "ADD_CARET_BELOW -- -e -e",
          "ADD_NEXT_OCCURRENCE -- -e -e",
          "SPLIT_INTO_LINES -- -e -e",
          "COLUMN_MODE -- -e -e",
          "WORD_WRAP -e -e -e",
          "ZOOM_IN -e -e -e",
          "ZOOM_OUT -e -e -e",
          "ZOOM_RESET -e -e -e",
          "THEME_LIGHT ce ce ce",
          "THEME_DARK -e -e -e",
          "THEME_SYSTEM -e -e -e",
          "SPLIT_HORIZONTAL -- -e -e",
          "SPLIT_VERTICAL -- -e -e",
          "UNSPLIT -- -- --",
          "FOCUS_OTHER_VIEW -- -- --",
          "RELOAD_GRAMMARS -e -e -e",
          "FOLD -- -- --",
          "UNFOLD -- -- --",
          "TOGGLE_FOLD -- -- --",
          "FOLD_ALL -- -- --",
          "UNFOLD_ALL -- -- --",
          "FIND -- -e -e",
          "FIND_NEXT -- -e -e",
          "FIND_PREVIOUS -- -e -e",
          "REPLACE -- -e --",
          "HYPERSEARCH -e -e -e",
          "NEXT_RESULT -- -- --",
          "PREVIOUS_RESULT -- -- --",
          "SEARCH_RESULTS -e -e -e",
          "REPLACE_IN_RESULTS -- -- --",
          "RESTORE_REPLACE_RUN -e -e -e",
          "LINE_ENDING_CRLF -- -e --",
          "LINE_ENDING_LF -- ce --",
          "LINE_ENDING_CR -- -e --",
          "NEW_WINDOW -e -e -e",
          "MOVE_TO_NEW_WINDOW -- -e -e",
          "NEXT_TAB -- -- -e",
          "PREVIOUS_TAB -- -- -e",
          "CLOSE_OTHERS -- -- -e",
          "KEYBOARD_SHORTCUTS -e -e -e",
          "COMMAND_LINE_OPTIONS -e -e -e",
          "USER_GUIDE -e -e -e",
          "SHOW_LOG -e -e -e",
          "DEBUG_LOG_FILE -e -e -e",
          "OPEN_LOG_FOLDER -e -e -e",
          "OPEN_SETTINGS_FOLDER -e -e -e",
          "REPORT_ISSUE -e -e -e",
          "ABOUT -e -e -e",
          "SORT_LINES_ASCENDING -- -e --",
          "SORT_LINES_DESCENDING -- -e --",
          "SORT_LINES_IGNORE_CASE -- -e --",
          "REMOVE_DUPLICATE_LINES -- -e --",
          "TRIM_TRAILING_WHITESPACE -- -e --",
          "COMPLETE_WORD -- -e --",
          "SELECT_TO_MATCHING_BRACKET -- -e -e",
          "GO_TO_MATCHING_BRACKET -- -e -e",
          "TOGGLE_BOOKMARK -- -e -e",
          "NEXT_BOOKMARK -- -- --",
          "PREVIOUS_BOOKMARK -- -- --",
          "CLEAR_BOOKMARKS -- -- --",
          "BOOKMARKS_LIST -e -e -e",
          "EDIT_BOOKMARK_COMMENT -- -e -e",
          "SHOW_WHITESPACE -e -e -e",
          "SHOW_LINE_ENDINGS -e -e -e",
          "OVERVIEW_STRIP ce ce ce",
          "LINE_NUMBERS ce ce ce",
          "TOOLBAR ce ce ce",
          "COMMAND_PALETTE -e -e -e",
          "WORKSPACE_BROWSER -e -e -e",
          "GO_BACK -- -- -e",
          "GO_FORWARD -- -- --",
          "SWITCH_DOCUMENT -- -e -e",
          "COMPARE_DOCUMENTS -- -- -e",
          "COMPARE_WITH_DISK -- -e -e",
          "CARET_LEFT -- -e -e",
          "CARET_RIGHT -- -e -e",
          "CARET_UP -- -e -e",
          "CARET_DOWN -- -e -e",
          "WORD_LEFT -- -e -e",
          "WORD_RIGHT -- -e -e",
          "LINE_START -- -e -e",
          "LINE_END -- -e -e",
          "PAGE_UP -- -e -e",
          "PAGE_DOWN -- -e -e",
          "DOCUMENT_START -- -e -e",
          "DOCUMENT_END -- -e -e",
          "SELECT_LEFT -- -e -e",
          "SELECT_RIGHT -- -e -e",
          "SELECT_UP -- -e -e",
          "SELECT_DOWN -- -e -e",
          "SELECT_WORD_LEFT -- -e -e",
          "SELECT_WORD_RIGHT -- -e -e",
          "SELECT_LINE_START -- -e -e",
          "SELECT_LINE_END -- -e -e",
          "SELECT_PAGE_UP -- -e -e",
          "SELECT_PAGE_DOWN -- -e -e",
          "SELECT_DOCUMENT_START -- -e -e",
          "SELECT_DOCUMENT_END -- -e -e",
          "BLOCK_LEFT -- -e -e",
          "BLOCK_RIGHT -- -e -e",
          "BLOCK_UP -- -e -e",
          "BLOCK_DOWN -- -e -e",
          "INSERT_TAB -- -e --",
          "NEWLINE -- -e --",
          "DELETE_BACK -- -e --",
          "DELETE_FORWARD -- -e --",
          "CANCEL -- -e -e",
          "reopen - e -",
          "saveEncoding - e -");

  @TempDir Path temp;

  private Harness harness;
  private CommandStates states;

  @BeforeEach
  void create() {
    harness = new Harness(temp);
    states = new CommandStates(harness.application);
  }

  @AfterEach
  void close() throws InterruptedException {
    harness.application.documents().dispose();
    harness.close();
  }

  private static String state(boolean checked, boolean enabled) {
    return (checked ? "c" : "-") + (enabled ? "e" : "-");
  }

  @Test
  void statesFollowTheSelectedDocument() throws Exception {
    String[][] cells = new String[CommandId.values().length + 2][3];
    record(cells, 0);
    harness.open(Files.writeString(temp.resolve("text.txt"), "one\ntwo\n"));
    record(cells, 1);
    Path binary = Files.write(temp.resolve("data.bin"), ShellCommandsTest.BINARY);
    harness.application.documents().open(List.of(binary));
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return harness.view.openBinary != null;
          }
        },
        "binary prompt");
    harness.view.openBinary.run();
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return harness.application.documents().count() == 2;
          }
        },
        "binary view");
    harness.awaitIdle("binary open");
    record(cells, 2);
    StringBuilder table = new StringBuilder();
    for (int i = 0; i < cells.length; i++) {
      String name =
          i < CommandId.values().length
              ? CommandId.values()[i].name()
              : i == CommandId.values().length ? "reopen" : "saveEncoding";
      if (table.length() > 0) {
        table.append('\n');
      }
      table.append(name).append(' ').append(String.join(" ", cells[i]));
    }
    assertEquals(EXPECTED, table.toString());
  }

  private void record(String[][] cells, int column) {
    CommandId[] commands = CommandId.values();
    for (int i = 0; i < commands.length; i++) {
      cells[i][column] = state(states.checked(commands[i]), states.enabled(commands[i]));
    }
    cells[commands.length][column] = states.reopenEnabled() ? "e" : "-";
    cells[commands.length + 1][column] = states.saveEncodingEnabled() ? "e" : "-";
  }

  @Test
  void undoAndRedoFollowTheHistoryAndCutAndCopyTheSelection() throws Exception {
    harness.open(Files.writeString(temp.resolve("edits.txt"), "one\n"));
    EditorController editor = harness.application.documents().activeEditor();
    editor.type("x");
    assertEquals(true, states.enabled(CommandId.UNDO));
    assertEquals(false, states.enabled(CommandId.REDO));
    editor.undo();
    assertEquals(false, states.enabled(CommandId.UNDO));
    assertEquals(true, states.enabled(CommandId.REDO));
    editor.selectFromView(0, 3);
    assertEquals(true, states.enabled(CommandId.CUT));
    assertEquals(true, states.enabled(CommandId.COPY));
    harness.application.execute(CommandId.READ_ONLY);
    assertEquals(false, states.enabled(CommandId.CUT));
    assertEquals(false, states.enabled(CommandId.REDO));
    assertEquals(true, states.enabled(CommandId.COPY));
    harness.application.execute(CommandId.READ_ONLY);
    editor.goTo(1, 1);
    assertEquals(false, states.enabled(CommandId.CUT));
    assertEquals(false, states.enabled(CommandId.COPY));
  }

  @Test
  void saveFollowsTheRoundTripGuardAndSaveAsStays() throws Exception {
    harness.open(Files.write(temp.resolve("guarded.xml"), SaveRefusalTest.NO_ROUND_TRIP));
    assertEquals(false, states.enabled(CommandId.SAVE));
    assertEquals(true, states.enabled(CommandId.SAVE_AS));
    harness.application.execute(CommandId.READ_ONLY);
    assertEquals(true, states.enabled(CommandId.SAVE));
  }

  @Test
  void statusFieldsDescribeThePrimaryCaret() throws Exception {
    assertEquals(StatusFields.NONE, states.status());
    harness.open(Files.writeString(temp.resolve("tabs.txt"), "a\tb\r\nsecond\r\n"));
    harness.application.documents().activeEditor().selectFromView(0, 3);
    StatusFields fields = states.status();
    assertEquals(UiText.STATUS_POSITION.format(1, 6), fields.position());
    assertEquals(UiText.STATUS_SELECTION.format(3), fields.selection());
    assertEquals("UTF-8", fields.encoding());
    assertEquals(UiText.STATUS_CRLF.text(), fields.lineEnding());
    assertEquals(UiText.STATUS_PLAIN_TEXT.text(), fields.language());
    assertEquals("", fields.readOnly());
  }

  @Test
  void marksAreCheckedAndBookmarkMovesNeedABookmark() throws Exception {
    harness.open(Files.writeString(temp.resolve("marks.txt"), "one\ntwo\n"));
    harness.application.execute(CommandId.SHOW_WHITESPACE);
    assertEquals(true, states.checked(CommandId.SHOW_WHITESPACE));
    assertEquals(false, states.checked(CommandId.SHOW_LINE_ENDINGS));
    harness.application.execute(CommandId.TOGGLE_BOOKMARK);
    assertEquals(true, states.enabled(CommandId.NEXT_BOOKMARK));
    assertEquals(true, states.enabled(CommandId.CLEAR_BOOKMARKS));
    harness.application.execute(CommandId.CLEAR_BOOKMARKS);
    assertEquals(false, states.enabled(CommandId.PREVIOUS_BOOKMARK));
  }

  @Test
  void resultCommandsFollowTheRetainedSets() throws Exception {
    harness.open(Files.writeString(temp.resolve("results.txt"), "key\nkey\n"));
    final SearchController search = harness.application.search();
    search.run(SearchControllerTest.form("key", SearchScope.CURRENT_DOCUMENT, ""));
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !search.running();
          }
        },
        "search ends");
    assertEquals(true, states.checked(CommandId.SEARCH_RESULTS));
    assertEquals(true, states.enabled(CommandId.NEXT_RESULT));
    assertEquals(true, states.enabled(CommandId.REPLACE_IN_RESULTS));
    harness.application.execute(CommandId.REPLACE_IN_RESULTS);
    assertEquals(true, harness.application.replace().previewOpen());
    harness.application.execute(CommandId.SEARCH_RESULTS);
    assertEquals(false, states.checked(CommandId.SEARCH_RESULTS));
    search.clearAll();
    assertEquals(false, states.enabled(CommandId.PREVIOUS_RESULT));
    harness.application.execute(CommandId.OVERVIEW_STRIP);
    assertEquals(false, states.checked(CommandId.OVERVIEW_STRIP));
  }
}
