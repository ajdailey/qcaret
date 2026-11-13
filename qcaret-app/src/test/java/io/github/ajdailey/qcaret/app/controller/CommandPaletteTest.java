package io.github.ajdailey.qcaret.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.command.CommandGroup;
import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.command.Fuzzy;
import io.github.ajdailey.qcaret.app.view.PaletteEntry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The command palette's fuzzy filter, its rows and running a chosen command. */
class CommandPaletteTest {
  @TempDir Path temp;

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

  private PaletteEntry first(String query) {
    harness.application.execute(CommandId.COMMAND_PALETTE);
    return harness.view.palette.filter(query).get(0);
  }

  @Test
  void fuzzyScoresInOrderCharsAndFavorsWordStartsAndRuns() {
    assertEquals(0, Fuzzy.score("", "Save"));
    assertEquals(-1, Fuzzy.score("sv x", "Save"));
    assertTrue(Fuzzy.score("ww", "Word Wrap") > Fuzzy.score("ww", "Show Whitespace x"));
    assertTrue(Fuzzy.score("sav", "Save") > Fuzzy.score("sav", "Show Lavender"));
  }

  @Test
  void anEmptyQueryListsEveryMenuCommandInMenuOrder() {
    harness.application.execute(CommandId.COMMAND_PALETTE);
    List<PaletteEntry> all = harness.view.palette.filter("");
    int menuCommands = 0;
    for (CommandId command : CommandId.values()) {
      if (command.group().menu() && command != CommandId.COMMAND_PALETTE) {
        menuCommands++;
      }
    }
    assertEquals(menuCommands, all.size());
    assertEquals(CommandId.NEW, all.get(0).command());
    for (PaletteEntry entry : all) {
      assertFalse(entry.command().group() == CommandGroup.EDITOR, entry.title());
    }
  }

  @Test
  void rowsShowKeysCascadeTitlesAndEnabledState() {
    PaletteEntry wrap = first("word wrap");
    assertEquals(CommandId.WORD_WRAP, wrap.command());
    assertTrue(wrap.enabled());
    PaletteEntry save = first("save as");
    assertEquals(CommandId.SAVE_AS, save.command());
    assertEquals(harness.application.keymap().keysText(CommandId.SAVE_AS), save.keys());
    assertFalse(save.enabled());
    assertEquals("Sort Lines: Descending", first("sort desc").title());
  }

  @Test
  void aTitleThatStartsWithItsCascadeWordShowsOnceAndTheCascadeWordStillMatches() {
    harness.application.execute(CommandId.COMMAND_PALETTE);
    String found = null;
    for (PaletteEntry entry : harness.view.palette.filter("split")) {
      if (entry.command() == CommandId.SPLIT_HORIZONTAL) {
        found = entry.title();
      }
    }
    assertEquals("Split Horizontally", found);
    assertEquals("Split: Unsplit", first("split unsplit").title());
    assertEquals("Bookmarks List", first("bookmarks list").title());
  }

  @Test
  void runExecutesOnlyAnEnabledCommand() throws Exception {
    harness.application.execute(CommandId.COMMAND_PALETTE);
    harness.view.palette.run(CommandId.SPLIT_VERTICAL);
    assertEquals(0, harness.application.documents().count());
    harness.view.palette.run(CommandId.SHOW_LINE_ENDINGS);
    assertTrue(harness.application.documents().lineEndingsShown());
    harness.open(Files.writeString(temp.resolve("p.txt"), "b\na\n"));
    harness.view.palette.run(CommandId.SORT_LINES_ASCENDING);
    harness.awaitIdle("sort");
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return harness
                .application
                .documents()
                .activeDocument()
                .text()
                .toString()
                .equals("a\nb\n");
          }
        },
        "sorted");
  }
}
