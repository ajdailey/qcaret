package io.github.ajdailey.qcaret.core.syntax;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/** Parsing, schema validation, DTD refusal and reference checks. */
class GrammarReaderTest {
  private static String invalid(final String xml) {
    return assertThrows(
            InvalidGrammarException.class,
            new Executable() {
              @Override
              public void execute() throws Exception {
                Tokens.read(xml);
              }
            })
        .getMessage();
  }

  @Test
  void readsEveryElement() throws Exception {
    Grammar g =
        Tokens.read(
            "<grammar id=\"mock\" name=\"Mock\" version=\"3\">"
                + "<detect><extension>MCK</extension><fileName>Mockfile</fileName>"
                + "<firstLine>^#!mock</firstLine></detect>"
                + "<comments line=\"--\" blockStart=\"{-\" blockEnd=\"-}\"/>"
                + "<brackets><pair open=\"(\" close=\")\"/></brackets>"
                + "<autoClose><pair open=\"'\" close=\"'\"/></autoClose>"
                + "<indent increase=\"do$\"/><folding mode=\"markers\" begin=\"do\" end=\"end\"/>"
                + "<context id=\"main\"><keywords token=\"keyword\" ignoreCase=\"true\">"
                + " do  end </keywords><include context=\"other\"/></context>"
                + "<context id=\"other\"><span token=\"string\" begin=\"'\" end=\"'\"/></context>"
                + "</grammar>");
    assertEquals("mock", g.id());
    assertEquals("3", g.version());
    assertEquals("mck", g.extensions().get(0));
    assertEquals("Mockfile", g.fileNames().get(0));
    assertEquals("--", g.comments().line());
    assertTrue(g.comments().hasBlock());
    assertEquals(")", g.brackets().get(0)[1]);
    assertEquals("'", g.autoClose().get(0)[0]);
    assertNull(g.indentDecrease());
    assertEquals(Grammar.FoldMode.MARKERS, g.folding().mode());
    Grammar.Keywords k = (Grammar.Keywords) g.contexts().get("main").get(0);
    assertEquals(2, k.words().size());
    assertTrue(k.ignoreCase());
    assertEquals("_", k.wordChars());
  }

  @Test
  void aDoctypeIsRefusedSoNoEntityIsRead() {
    String message =
        invalid(
            "<?xml version=\"1.0\"?><!DOCTYPE grammar [<!ENTITY x SYSTEM \"file:///etc/hosts\">]>"
                + "<grammar id=\"mock\" name=\"&x;\"><context id=\"main\"/></grammar>");
    assertTrue(message.contains("DOCTYPE"), message);
  }

  @Test
  void aSchemaErrorNamesTheFileAndLine() {
    String message =
        invalid(
            "<grammar id=\"mock\" name=\"Mock\">\n<context id=\"main\">\n<bogus/></context></grammar>");
    assertTrue(message.startsWith("test.xml:3:"), message);
    assertTrue(
        invalid("<grammar id=\"Bad Id\" name=\"x\"><context id=\"main\"/></grammar>")
            .startsWith("test.xml:1:"));
    invalid(
        "<grammar id=\"mock\" name=\"x\"><context id=\"main\">"
            + "<match regex=\"a\" token=\"bold\"/></context></grammar>");
  }

  @Test
  void aBadRegexOrAMissingContextIsRefused() {
    assertTrue(
        invalid(
                "<grammar id=\"mock\" name=\"x\"><context id=\"main\">"
                    + "<match regex=\"(a\" token=\"string\"/></context></grammar>")
            .contains("invalid regex"));
    assertTrue(
        invalid(
                "<grammar id=\"mock\" name=\"x\"><context id=\"main\">"
                    + "<include context=\"nowhere\"/></context></grammar>")
            .contains("nowhere"));
    assertTrue(
        invalid("<grammar id=\"mock\" name=\"x\"><context id=\"other\"/></grammar>")
            .contains("main"));
    assertTrue(
        invalid(
                "<grammar id=\"mock\" name=\"x\"><folding mode=\"brackets\" open=\"{(\" close=\"}\"/>"
                    + "<context id=\"main\"/></grammar>")
            .contains("folding"));
  }
}
