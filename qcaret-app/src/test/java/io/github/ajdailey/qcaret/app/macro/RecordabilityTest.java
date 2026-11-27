package io.github.ajdailey.qcaret.app.macro;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.ajdailey.qcaret.app.command.CommandGroup;
import io.github.ajdailey.qcaret.app.command.CommandId;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Each command group's members against the recording table: a group has one class, and the listed
 * commands are its exceptions.
 */
class RecordabilityTest {
  @Test
  void everyCommandHasTheClassOfTheRecordingTable() {
    Map<CommandGroup, Recordability> usual =
        new EnumMap<CommandGroup, Recordability>(CommandGroup.class);
    usual.put(CommandGroup.FILE, Recordability.NOTICE);
    usual.put(CommandGroup.EDIT, Recordability.COMMAND);
    usual.put(CommandGroup.SELECTION, Recordability.COMMAND);
    usual.put(CommandGroup.SEARCH, Recordability.NOTICE);
    usual.put(CommandGroup.VIEW, Recordability.SILENT);
    usual.put(CommandGroup.ENCODING, Recordability.COMMAND);
    usual.put(CommandGroup.TOOLS, Recordability.NOTICE);
    usual.put(CommandGroup.MACROS, Recordability.NOTICE);
    usual.put(CommandGroup.WINDOW, Recordability.NOTICE);
    usual.put(CommandGroup.HELP, Recordability.SILENT);
    usual.put(CommandGroup.EDITOR, Recordability.COMMAND);
    Map<CommandId, Recordability> exceptions =
        new EnumMap<CommandId, Recordability>(CommandId.class);
    for (CommandId c :
        Set.of(
            CommandId.TAB_WIDTH_OTHER,
            CommandId.INSERT_DATE_TIME,
            CommandId.INSERT_NUMBER_SEQUENCE,
            CommandId.COMPLETE_WORD,
            CommandId.FIND,
            CommandId.REPLACE,
            CommandId.FIND_NEXT,
            CommandId.FIND_PREVIOUS,
            CommandId.GO_TO_LINE)) {
      exceptions.put(c, Recordability.OWN_STEP);
    }
    exceptions.put(CommandId.READ_ONLY, Recordability.NOTICE);
    exceptions.put(CommandId.COMPARE_DOCUMENTS, Recordability.NOTICE);
    exceptions.put(CommandId.COMPARE_WITH_DISK, Recordability.NOTICE);
    for (CommandId c :
        Set.of(
            CommandId.GO_TO_MATCHING_BRACKET,
            CommandId.SELECT_TO_MATCHING_BRACKET,
            CommandId.TOGGLE_BOOKMARK,
            CommandId.NEXT_BOOKMARK,
            CommandId.PREVIOUS_BOOKMARK,
            CommandId.CLEAR_BOOKMARKS,
            CommandId.FOLD,
            CommandId.UNFOLD,
            CommandId.TOGGLE_FOLD,
            CommandId.FOLD_ALL,
            CommandId.UNFOLD_ALL)) {
      exceptions.put(c, Recordability.COMMAND);
    }
    for (CommandId c :
        Set.of(
            CommandId.SEARCH_RESULTS,
            CommandId.DOCUMENT_STATISTICS,
            CommandId.MINIMIZE,
            CommandId.ZOOM_WINDOW,
            CommandId.RECORD_MACRO)) {
      exceptions.put(c, Recordability.SILENT);
    }
    for (CommandId command : CommandId.values()) {
      Recordability expected = exceptions.getOrDefault(command, usual.get(command.group()));
      assertEquals(expected, Recordability.of(command), command.name());
    }
  }
}
