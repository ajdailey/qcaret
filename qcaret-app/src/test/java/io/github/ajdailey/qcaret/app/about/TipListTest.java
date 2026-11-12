package io.github.ajdailey.qcaret.app.about;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.command.Keymap;
import io.github.ajdailey.qcaret.app.command.ShortcutOverrides;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Tip text takes each command's first key from the keymap in use, or none. */
class TipListTest {
  private static int indexOf(UiText text) {
    for (int i = 0; i < TipList.size(); i++) {
      if (TipList.tip(i).text() == text) {
        return i;
      }
    }
    throw new AssertionError(text.name());
  }

  @Test
  void theKeyFollowsThePlatform() {
    int quickOpen = indexOf(UiText.TIP_QUICK_OPEN);
    assertTrue(
        TipList.text(quickOpen, Keymap.defaults(false, true))
            .startsWith("File > Quick Open (Ctrl+P) finds"));
    assertTrue(
        TipList.text(quickOpen, Keymap.defaults(false, false))
            .startsWith("File > Quick Open (Ctrl+P) finds"));
    assertTrue(
        TipList.text(quickOpen, Keymap.defaults(true, false))
            .startsWith("File > Quick Open (Cmd+P) finds"));
    int goToSymbol = indexOf(UiText.TIP_GO_TO_SYMBOL);
    assertTrue(
        TipList.text(goToSymbol, Keymap.defaults(true, false))
            .startsWith("Edit > Go to Symbol (Cmd+R) lists"));
  }

  @Test
  void anUnboundCommandsTipHasNoKey() {
    Keymap keymap = Keymap.defaults(false, true);
    assertEquals("", keymap.menuKeyText(CommandId.ALWAYS_ON_TOP));
    assertTrue(
        TipList.text(indexOf(UiText.TIP_ALWAYS_ON_TOP), keymap)
            .startsWith("View > Always on Top keeps"));
    for (int i = 0; i < TipList.size(); i++) {
      String text = TipList.text(i, keymap);
      assertFalse(text.contains("()"), text);
      assertFalse(text.contains("%"), text);
      assertFalse(text.contains("  "), text);
    }
  }

  @Test
  void theUsersKeysReplaceTheDefaults() {
    List<String> refused = new ArrayList<String>();
    Keymap keymap =
        Keymap.defaults(false, true)
            .withOverrides(
                ShortcutOverrides.parse(
                    Map.of(
                        ShortcutOverrides.PREFIX + CommandId.QUICK_OPEN.name(),
                        "Mod+Alt+K",
                        ShortcutOverrides.PREFIX + CommandId.GO_TO_SYMBOL.name(),
                        ""),
                    refused));
    assertEquals(List.of(), refused);
    assertTrue(
        TipList.text(indexOf(UiText.TIP_QUICK_OPEN), keymap)
            .startsWith("File > Quick Open (Ctrl+Alt+K) finds"));
    assertTrue(
        TipList.text(indexOf(UiText.TIP_GO_TO_SYMBOL), keymap)
            .startsWith("Edit > Go to Symbol lists"));
  }

  @Test
  void anyIndexNamesATip() {
    Keymap keymap = Keymap.defaults(false, true);
    assertEquals(TipList.text(0, keymap), TipList.text(TipList.size(), keymap));
    assertEquals(TipList.text(TipList.size() - 1, keymap), TipList.text(-1, keymap));
  }

  @Test
  void everyTipIsDistinct() {
    Set<UiText> texts = new HashSet<UiText>();
    for (int i = 0; i < TipList.size(); i++) {
      TipList.Tip tip = TipList.tip(i);
      assertTrue(texts.add(tip.text()), tip.text().name());
      assertTrue(tip.text().name().startsWith("TIP_"), tip.text().name());
    }
    assertTrue(TipList.size() >= 30, "tips: " + TipList.size());
  }
}
