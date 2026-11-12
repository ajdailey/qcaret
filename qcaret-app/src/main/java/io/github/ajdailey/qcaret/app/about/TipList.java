package io.github.ajdailey.qcaret.app.about;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.command.Keymap;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import java.util.ArrayList;
import java.util.List;

/**
 * Help > Tip of the Day's tips, in the order shown. A tip's text names a menu path and takes the
 * first key of its command from the {@link Keymap} at show time, so the text follows the platform
 * and the user's keys; a tip whose command has no key shows none.
 */
public final class TipList {
  /**
   * One tip.
   *
   * @param text the pattern; takes {@link UiText#TIP_KEY} formatted, or empty, where the command
   *     has one
   * @param command the command whose key the text names, or null for a tip without one
   */
  public record Tip(UiText text, CommandId command) {}

  private static final List<Tip> TIPS = build();

  private TipList() {}

  private static List<Tip> build() {
    List<Tip> list = new ArrayList<Tip>();
    add(list, UiText.TIP_QUICK_OPEN, CommandId.QUICK_OPEN);
    add(list, UiText.TIP_GO_TO_SYMBOL, CommandId.GO_TO_SYMBOL);
    add(list, UiText.TIP_QUICK_OPEN_SYMBOL, CommandId.QUICK_OPEN);
    add(list, UiText.TIP_SWITCH_DOCUMENT, CommandId.SWITCH_DOCUMENT);
    add(list, UiText.TIP_TAB_LIST, null);
    add(list, UiText.TIP_COMMAND_PALETTE, CommandId.COMMAND_PALETTE);
    add(list, UiText.TIP_HYPERSEARCH, CommandId.HYPERSEARCH);
    add(list, UiText.TIP_REPLACE_IN_RESULTS, CommandId.REPLACE_IN_RESULTS);
    add(list, UiText.TIP_ADD_NEXT_OCCURRENCE, CommandId.ADD_NEXT_OCCURRENCE);
    add(list, UiText.TIP_SELECT_ALL_OCCURRENCES, CommandId.SELECT_ALL_OCCURRENCES);
    add(list, UiText.TIP_ADD_CARET, CommandId.ADD_CARET_BELOW);
    add(list, UiText.TIP_SPLIT_INTO_LINES, CommandId.SPLIT_INTO_LINES);
    add(list, UiText.TIP_COLUMN_MODE, CommandId.COLUMN_MODE);
    add(list, UiText.TIP_EXPAND_SELECTION, CommandId.EXPAND_SELECTION);
    add(list, UiText.TIP_UNDO_SELECTION, CommandId.UNDO_SELECTION);
    add(list, UiText.TIP_FOLD_ALL, CommandId.FOLD_ALL);
    add(list, UiText.TIP_TOGGLE_BOOKMARK, CommandId.TOGGLE_BOOKMARK);
    add(list, UiText.TIP_BOOKMARKS_LIST, CommandId.BOOKMARKS_LIST);
    add(list, UiText.TIP_COMPARE_DOCUMENTS, CommandId.COMPARE_DOCUMENTS);
    add(list, UiText.TIP_COMPARE_WITH_DISK, CommandId.COMPARE_WITH_DISK);
    add(list, UiText.TIP_CHECKSUM_FILES, CommandId.SHA256_FILES);
    add(list, UiText.TIP_VERIFY_CHECKSUMS, CommandId.VERIFY_CHECKSUMS);
    add(list, UiText.TIP_EDITOR_COLORS, CommandId.PREFERENCES);
    add(list, UiText.TIP_ALWAYS_ON_TOP, CommandId.ALWAYS_ON_TOP);
    add(list, UiText.TIP_SAVE_COPY_AS, CommandId.SAVE_COPY_AS);
    add(list, UiText.TIP_PER_FILE_SETTINGS, null);
    add(list, UiText.TIP_WORKSPACE_BROWSER, CommandId.WORKSPACE_BROWSER);
    add(list, UiText.TIP_BROWSER_DRAG, null);
    add(list, UiText.TIP_MOVE_TAB, CommandId.MOVE_TO_NEW_WINDOW);
    add(list, UiText.TIP_SPLIT, CommandId.SPLIT_VERTICAL);
    add(list, UiText.TIP_GO_BACK, CommandId.GO_BACK);
    add(list, UiText.TIP_REOPEN_CLOSED, CommandId.REOPEN_CLOSED);
    add(list, UiText.TIP_TOGGLE_LINE_COMMENT, CommandId.TOGGLE_LINE_COMMENT);
    add(list, UiText.TIP_COMPLETE_WORD, CommandId.COMPLETE_WORD);
    add(list, UiText.TIP_KEYBOARD_SHORTCUTS, CommandId.KEYBOARD_SHORTCUTS);
    return List.copyOf(list);
  }

  private static void add(List<Tip> list, UiText text, CommandId command) {
    list.add(new Tip(text, command));
  }

  /**
   * Returns a tip.
   *
   * @param index the tip, from 0 to {@link #size} - 1
   * @return the tip
   */
  public static Tip tip(int index) {
    return TIPS.get(index);
  }

  /**
   * Returns the number of tips.
   *
   * @return at least 1
   */
  public static int size() {
    return TIPS.size();
  }

  /**
   * Returns a tip's text with its command's first key.
   *
   * @param index the tip; wraps, so any value names one
   * @param keymap the bindings in use
   * @return the text
   */
  public static String text(int index, Keymap keymap) {
    Tip tip = TIPS.get(Math.floorMod(index, TIPS.size()));
    if (tip.command() == null) {
      return tip.text().text();
    }
    String key = keymap.menuKeyText(tip.command());
    return tip.text().format(key.isEmpty() ? "" : UiText.TIP_KEY.format(key));
  }
}
