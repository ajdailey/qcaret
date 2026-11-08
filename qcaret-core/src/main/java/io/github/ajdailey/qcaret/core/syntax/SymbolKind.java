package io.github.ajdailey.qcaret.core.syntax;

/** What a grammar's symbol rule finds; Goto Symbol shows it beside the name. */
public enum SymbolKind {
  /** A function or procedure. */
  FUNCTION,
  /** A function declared inside a type. */
  METHOD,
  /** A class. */
  CLASS,
  /** An interface or protocol. */
  INTERFACE,
  /** A struct or record. */
  STRUCT,
  /** An enumeration. */
  ENUM,
  /** A trait or mixin. */
  TRAIT,
  /** Another type: an alias, a message, a typedef. */
  TYPE,
  /** A module or package. */
  MODULE,
  /** A namespace. */
  NAMESPACE,
  /** A macro. */
  MACRO,
  /** A named constant. */
  CONSTANT,
  /** A variable. */
  VARIABLE,
  /** A property or field. */
  PROPERTY,
  /** A heading or a configuration section. */
  SECTION,
  /** A build target or stage. */
  TARGET,
  /** A jump label. */
  LABEL,
  /** A style sheet selector. */
  SELECTOR,
  /** A database table. */
  TABLE,
  /** A database view. */
  VIEW;

  private static final SymbolKind[] VALUES = values();

  /**
   * Returns the kind a grammar file names.
   *
   * @param name the lower-case name, such as {@code function}
   * @return the kind
   * @throws IllegalArgumentException for an unknown name
   */
  public static SymbolKind fromName(String name) {
    for (SymbolKind kind : VALUES) {
      if (kind.label().equals(name)) {
        return kind;
      }
    }
    throw new IllegalArgumentException("unknown symbol kind " + name);
  }

  /**
   * Returns the name a grammar file uses.
   *
   * @return the lower-case name
   */
  public String label() {
    return GrammarSet.asciiLower(name());
  }
}
