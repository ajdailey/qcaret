package io.github.ajdailey.qcaret.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.core.syntax.Grammar;
import io.github.ajdailey.qcaret.core.syntax.GrammarReader;
import io.github.ajdailey.qcaret.core.syntax.GrammarSet;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Recent grammars, Plain Text and the category submenus of the Syntax menu. */
class SyntaxMenuTest {
  private static final List<Grammar> BUILT_IN = GrammarSet.readBuiltIns(new GrammarReader());

  private static List<Grammar> byName(List<Grammar> grammars) {
    return GrammarSet.of(grammars, Map.of(), new ArrayList<String>()).byName();
  }

  private static Grammar user(String id, String name, String category) throws Exception {
    String xml =
        "<grammar id=\""
            + id
            + "\" name=\""
            + name
            + "\""
            + (category == null ? "" : " category=\"" + category + "\"")
            + "><context id=\"main\"/></grammar>";
    return new GrammarReader()
        .read(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), id + ".xml");
  }

  private static List<String> ids(List<CommandStates.SyntaxChoice> choices) {
    List<String> out = new ArrayList<String>();
    for (CommandStates.SyntaxChoice choice : choices) {
      out.add(choice.id());
    }
    return out;
  }

  private static SyntaxMenu.Category category(SyntaxMenu menu, UiText label) {
    for (SyntaxMenu.Category c : menu.categories()) {
      if (c.label() == label) {
        return c;
      }
    }
    return null;
  }

  @Test
  void builtInGrammarsFillTheFourCategoriesInNameOrder() {
    SyntaxMenu menu = SyntaxMenu.of(byName(BUILT_IN), List.of(), "java");
    assertEquals(GrammarSet.PLAIN, menu.plain().id());
    assertFalse(menu.plain().checked());
    List<UiText> labels = new ArrayList<UiText>();
    int count = 0;
    int checked = 0;
    for (SyntaxMenu.Category c : menu.categories()) {
      labels.add(c.label());
      count += c.choices().size();
      String previous = "";
      for (CommandStates.SyntaxChoice choice : c.choices()) {
        assertTrue(previous.compareToIgnoreCase(choice.name()) <= 0, choice.name());
        previous = choice.name();
        checked += choice.checked() ? 1 : 0;
      }
    }
    assertEquals(
        List.of(
            UiText.MENU_SYNTAX_PROGRAMMING_A_M,
            UiText.MENU_SYNTAX_PROGRAMMING_N_Z,
            UiText.MENU_SYNTAX_MARKUP,
            UiText.MENU_SYNTAX_BUILD),
        labels);
    assertEquals(GrammarSet.BUILT_IN.size() - 1, count);
    assertEquals(1, checked);
    List<String> am = ids(category(menu, UiText.MENU_SYNTAX_PROGRAMMING_A_M).choices());
    List<String> nz = ids(category(menu, UiText.MENU_SYNTAX_PROGRAMMING_N_Z).choices());
    assertTrue(am.contains("java") && am.contains("csharp") && am.contains("kotlin"), "" + am);
    assertTrue(nz.contains("python") && nz.contains("objectivec") && nz.contains("vbnet"), "" + nz);
    assertTrue(ids(category(menu, UiText.MENU_SYNTAX_MARKUP).choices()).contains("json"));
    assertTrue(ids(category(menu, UiText.MENU_SYNTAX_BUILD).choices()).contains("dockerfile"));
  }

  @Test
  void aUserGrammarGoesInItsNamedCategoryElseInUser() throws Exception {
    List<Grammar> grammars = new ArrayList<Grammar>(BUILT_IN);
    grammars.add(user("mockdata", "Mock Data", "markup"));
    grammars.add(user("mocklang", "Mock Language", null));
    SyntaxMenu menu = SyntaxMenu.of(byName(grammars), List.of(), null);
    assertTrue(ids(category(menu, UiText.MENU_SYNTAX_MARKUP).choices()).contains("mockdata"));
    SyntaxMenu.Category last = menu.categories().get(menu.categories().size() - 1);
    assertEquals(UiText.MENU_SYNTAX_USER, last.label());
    assertEquals(List.of("mocklang"), ids(last.choices()));
  }

  @Test
  void recentGrammarsComeFirstWithoutPlainTextOrAnUnknownId() {
    SyntaxMenu menu =
        SyntaxMenu.of(byName(BUILT_IN), List.of("ruby", "missing", GrammarSet.PLAIN, "go"), "go");
    assertEquals(List.of("ruby", "go"), ids(menu.recent()));
    assertFalse(menu.recent().get(0).checked());
    assertTrue(menu.recent().get(1).checked());
  }

  @Test
  void noGrammarsGiveAnEmptyMenu() {
    assertTrue(SyntaxMenu.of(List.of(), List.of("java"), null).isEmpty());
    assertTrue(SyntaxMenu.EMPTY.isEmpty());
  }
}
