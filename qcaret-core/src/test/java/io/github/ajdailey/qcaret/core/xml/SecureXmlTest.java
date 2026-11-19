package io.github.ajdailey.qcaret.core.xml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.xml.validation.Schema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Document;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

/**
 * Each {@link SecureXml} configuration against external files and an entity-expansion bomb. Every
 * external file holds text that breaks the document or schema if read, so a passing parse proves it
 * was never opened.
 */
class SecureXmlTest {
  private static final String SCHEMA =
      "<xs:schema xmlns:xs=\"http://www.w3.org/2001/XMLSchema\">"
          + "<xs:element name=\"a\" type=\"xs:string\"/></xs:schema>";

  private static final ErrorHandler ERRORS =
      new ErrorHandler() {
        @Override
        public void warning(SAXParseException e) {
          // Not an error.
        }

        @Override
        public void error(SAXParseException e) throws SAXException {
          throw e;
        }

        @Override
        public void fatalError(SAXParseException e) throws SAXException {
          throw e;
        }
      };

  @TempDir Path dir;

  private String dtd;
  private String entity;

  @BeforeEach
  void writeExternalFiles() throws IOException {
    Path dtdFile = dir.resolve("bad.dtd");
    Files.writeString(dtdFile, "<!ELEMENT", StandardCharsets.UTF_8);
    Path entityFile = dir.resolve("bad.xml");
    Files.writeString(entityFile, "<unclosed", StandardCharsets.UTF_8);
    dtd = dtdFile.toUri().toString();
    entity = entityFile.toUri().toString();
  }

  private static String bomb(String root) {
    StringBuilder text = new StringBuilder("<!DOCTYPE " + root + " [<!ENTITY l0 'ha'>");
    for (int i = 1; i < 10; i++) {
      text.append("<!ENTITY l")
          .append(i)
          .append(" '")
          .append(("&l" + (i - 1) + ";").repeat(10))
          .append("'>");
    }
    return text.append("]><" + root + ">&l9;</" + root + ">").toString();
  }

  private static InputStream bytes(String text) {
    return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
  }

  private static void check(String text) throws SAXException, IOException {
    SecureXml.checkWellFormed(new InputSource(new StringReader(text)));
  }

  private static Schema schema() throws SAXException {
    return SecureXml.schema(bytes(SCHEMA), "test.xsd");
  }

  @Test
  void wellFormedReadsTheInternalSubsetAndNoExternalFile() throws Exception {
    check(
        "<!DOCTYPE a SYSTEM \""
            + dtd
            + "\" [<!ENTITY i 'x'><!ENTITY e SYSTEM \""
            + entity
            + "\"><!ENTITY % p SYSTEM \""
            + entity
            + "\"> %p;]><a>&i;&e;</a>");
  }

  @Test
  void wellFormedRefusesAnExpansionBomb() {
    SAXParseException e =
        assertThrows(
            SAXParseException.class,
            new Executable() {
              @Override
              public void execute() throws Throwable {
                check(bomb("l"));
              }
            });
    assertTrue(e.getMessage().contains("entity expansions"), e.getMessage());
  }

  @Test
  void aDocumentWithoutADoctypeParsesAndValidates() throws Exception {
    Document document = SecureXml.document(bytes("<a>x</a>"), schema(), ERRORS);
    assertEquals("x", document.getDocumentElement().getTextContent());
  }

  @Test
  void aDocumentRefusesEveryDoctype() {
    String[] texts = {
      "<!DOCTYPE a SYSTEM \"" + dtd + "\"><a>x</a>",
      "<!DOCTYPE a [<!ENTITY e SYSTEM \"" + entity + "\">]><a>&e;</a>",
      "<!DOCTYPE a [<!ENTITY % p SYSTEM \"" + entity + "\"> %p;]><a>x</a>",
      bomb("a")
    };
    for (final String text : texts) {
      SAXParseException e =
          assertThrows(
              SAXParseException.class,
              new Executable() {
                @Override
                public void execute() throws Throwable {
                  SecureXml.document(bytes(text), schema(), ERRORS);
                }
              });
      assertTrue(e.getMessage().contains("DOCTYPE"), e.getMessage());
    }
  }

  @Test
  void aSchemaReadsNoExternalFile() {
    final String[] texts = {
      "<!DOCTYPE xs:schema SYSTEM \"" + dtd + "\">" + SCHEMA,
      "<xs:schema xmlns:xs=\"http://www.w3.org/2001/XMLSchema\"><xs:include schemaLocation=\""
          + entity
          + "\"/></xs:schema>"
    };
    for (final String text : texts) {
      SAXException e =
          assertThrows(
              SAXException.class,
              new Executable() {
                @Override
                public void execute() throws Throwable {
                  SecureXml.schema(bytes(text), "test.xsd");
                }
              });
      assertTrue(e.getMessage().contains("accessExternal"), e.getMessage());
    }
  }

  @Test
  void aSchemaRefusesAnExpansionBomb() {
    String bomb = bomb("a");
    final String text =
        bomb.substring(0, bomb.indexOf("<a>")).replace("DOCTYPE a", "DOCTYPE xs:schema")
            + "<xs:schema xmlns:xs=\"http://www.w3.org/2001/XMLSchema\"><xs:annotation>"
            + "<xs:documentation>&l9;</xs:documentation></xs:annotation></xs:schema>";
    SAXException e =
        assertThrows(
            SAXException.class,
            new Executable() {
              @Override
              public void execute() throws Throwable {
                SecureXml.schema(bytes(text), "test.xsd");
              }
            });
    assertTrue(e.getMessage().contains("entity expansions"), e.getMessage());
  }
}
