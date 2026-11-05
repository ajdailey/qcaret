package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.core.syntax.Grammar;
import io.github.ajdailey.qcaret.core.syntax.GrammarSet;
import java.util.ArrayList;
import java.util.List;

/**
 * The entries of View > Syntax and the status bar's language popup: the recently chosen grammars,
 * Plain Text, then one submenu per category that holds a grammar. A grammar's {@link
 * Grammar#category()} picks its submenu; programming languages split by the first letter of the
 * name, A-M and N-Z; a grammar without a known category goes in User. Each list keeps the order of
 * {@link GrammarSet#byName()}.
 *
 * @param recent the recently chosen grammars, most recent first; Plain Text is never one
 * @param plain Plain Text, or null before the grammars load
 * @param categories the submenus that hold a grammar, in menu order
 */
public record SyntaxMenu(
    List<CommandStates.SyntaxChoice> recent,
    CommandStates.SyntaxChoice plain,
    List<SyntaxMenu.Category> categories) {
  /** The menu without a document or before the grammars load. */
  public static final SyntaxMenu EMPTY = new SyntaxMenu(List.of(), null, List.of());

  /**
   * One category submenu.
   *
   * @param label the submenu's label
   * @param choices its grammars, by name
   */
  public record Category(UiText label, List<CommandStates.SyntaxChoice> choices) {
    /** Copies the list. */
    public Category {
      choices = List.copyOf(choices);
    }
  }

  /** Copies the lists. */
  public SyntaxMenu {
    recent = List.copyOf(recent);
    categories = List.copyOf(categories);
  }

  /** The submenus in menu order. */
  private static final UiText[] LABELS = {
    UiText.MENU_SYNTAX_PROGRAMMING_A_M,
    UiText.MENU_SYNTAX_PROGRAMMING_N_Z,
    UiText.MENU_SYNTAX_MARKUP,
    UiText.MENU_SYNTAX_BUILD,
    UiText.MENU_SYNTAX_USER
  };

  /**
   * Builds the menu.
   *
   * @param grammars every grammar, plain text first, then by name
   * @param recent ids of the recently chosen grammars, most recent first; an id not in {@code
   *     grammars} is left out
   * @param active the selected document's grammar id, checked wherever it shows, or null
   * @return the menu
   */
  static SyntaxMenu of(List<Grammar> grammars, List<String> recent, String active) {
    List<List<CommandStates.SyntaxChoice>> lists =
        new ArrayList<List<CommandStates.SyntaxChoice>>();
    for (int i = 0; i < LABELS.length; i++) {
      lists.add(new ArrayList<CommandStates.SyntaxChoice>());
    }
    CommandStates.SyntaxChoice plain = null;
    for (Grammar g : grammars) {
      CommandStates.SyntaxChoice choice = choice(g, active);
      if (g.id().equals(GrammarSet.PLAIN)) {
        plain = choice;
      } else {
        lists.get(category(g)).add(choice);
      }
    }
    List<CommandStates.SyntaxChoice> recentChoices = new ArrayList<CommandStates.SyntaxChoice>();
    for (String id : recent) {
      for (Grammar g : grammars) {
        if (g.id().equals(id) && !id.equals(GrammarSet.PLAIN)) {
          recentChoices.add(choice(g, active));
        }
      }
    }
    List<Category> categories = new ArrayList<Category>();
    for (int i = 0; i < LABELS.length; i++) {
      if (!lists.get(i).isEmpty()) {
        categories.add(new Category(LABELS[i], lists.get(i)));
      }
    }
    return new SyntaxMenu(recentChoices, plain, categories);
  }

  /**
   * Reports whether the menu has no entry.
   *
   * @return true without a document or before the grammars load
   */
  public boolean isEmpty() {
    return plain == null && recent.isEmpty() && categories.isEmpty();
  }

  private static CommandStates.SyntaxChoice choice(Grammar g, String active) {
    return new CommandStates.SyntaxChoice(g.id(), g.name(), g.id().equals(active));
  }

  /** Returns the index into {@link #LABELS} of a grammar's submenu. */
  private static int category(Grammar g) {
    String category = g.category() == null ? "" : g.category();
    return switch (category) {
      case "programming" -> {
        char first = Character.toUpperCase(g.name().charAt(0));
        yield first >= 'N' && first <= 'Z' ? 1 : 0;
      }
      case "markup" -> 2;
      case "build" -> 3;
      default -> 4;
    };
  }
}
