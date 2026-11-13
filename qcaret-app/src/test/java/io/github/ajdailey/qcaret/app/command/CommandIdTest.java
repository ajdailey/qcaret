package io.github.ajdailey.qcaret.app.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

/**
 * Qualified titles: the cascade title, a colon and the item title, or the item title alone when it
 * starts with the cascade title as a whole word.
 */
class CommandIdTest {
  @Test
  void aQualifiedTitleNeverRepeatsItsCascadeWord() {
    for (CommandId command : CommandId.values()) {
      if (command.submenu() == null) {
        assertEquals(command.title(), command.qualifiedTitle(), command.name());
        continue;
      }
      String cascade = command.submenu().replace("&", "");
      String title = command.title();
      boolean repeats = title.equals(cascade) || title.startsWith(cascade + " ");
      String expected = repeats ? title : cascade + ": " + title;
      assertEquals(expected, command.qualifiedTitle(), command.name());
      assertFalse(command.qualifiedTitle().startsWith(cascade + ": " + cascade), command.name());
    }
    assertEquals("Split Horizontally", CommandId.SPLIT_HORIZONTAL.qualifiedTitle());
    assertEquals("Compare Documents", CommandId.COMPARE_DOCUMENTS.qualifiedTitle());
    assertEquals("Bookmarks List", CommandId.BOOKMARKS_LIST.qualifiedTitle());
    assertEquals("SHA-256: Generate", CommandId.SHA256_TEXT.qualifiedTitle());
    assertEquals("Split: Unsplit", CommandId.UNSPLIT.qualifiedTitle());
    assertEquals("Close Multiple Tabs: Close Others", CommandId.CLOSE_OTHERS.qualifiedTitle());
  }
}
