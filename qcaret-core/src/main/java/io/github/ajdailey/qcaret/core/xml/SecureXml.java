package io.github.ajdailey.qcaret.core.xml;

import java.io.IOException;
import java.io.InputStream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import org.w3c.dom.Document;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

/**
 * Every JDK XML parser and schema factory QCaret uses, each with secure processing on and external
 * DTD and schema access empty. Thread-safe: each call builds its own factory.
 */
public final class SecureXml {
  private SecureXml() {}

  /**
   * Loads a W3C XML schema. Permits the schema's own declarations; refuses to read an external DTD
   * or an imported or included schema.
   *
   * @param in the schema's bytes; not closed
   * @param systemId names the schema in messages
   * @return the schema
   * @throws SAXException when the schema does not load
   */
  public static Schema schema(InputStream in, String systemId) throws SAXException {
    SchemaFactory factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
    factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
    factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
    factory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
    return factory.newSchema(new StreamSource(in, systemId));
  }

  /**
   * Parses a namespace-aware DOM document, validated against a schema while it parses. Refuses any
   * DOCTYPE declaration, so no entity is declared or read; XInclude is off and entity references
   * are not expanded.
   *
   * @param in the document's bytes; not closed
   * @param schema validates the document
   * @param errors receives the parser's warnings and errors; a fatal error also throws
   * @return the document
   * @throws ParserConfigurationException when the JDK refuses a setting
   * @throws SAXException when the document is malformed, holds a DOCTYPE or is invalid
   * @throws IOException when the stream fails
   */
  public static Document document(InputStream in, Schema schema, ErrorHandler errors)
      throws ParserConfigurationException, SAXException, IOException {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
    factory.setXIncludeAware(false);
    factory.setExpandEntityReferences(false);
    factory.setNamespaceAware(true);
    factory.setSchema(schema);
    DocumentBuilder builder = factory.newDocumentBuilder();
    builder.setErrorHandler(errors);
    return builder.parse(in);
  }

  /**
   * Reads a document with SAX for XML 1.0 well-formedness only, not namespace aware and not
   * validating, and throws at the first fatal error. Permits a DOCTYPE and its internal subset,
   * whose entities expand within the JDK's secure-processing limits (64,000 expansions). Refuses to
   * read anything outside the source: external general and parameter entities and the external DTD
   * are not loaded.
   *
   * @param source the document, usually a character stream
   * @throws SAXParseException at the first fatal error, with its line and column
   * @throws SAXException when the parser stops for another reason
   * @throws IOException when the source fails
   */
  public static void checkWellFormed(InputSource source) throws SAXException, IOException {
    XMLReader reader;
    try {
      SAXParserFactory factory = SAXParserFactory.newInstance();
      factory.setNamespaceAware(false);
      factory.setValidating(false);
      factory.setXIncludeAware(false);
      factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      SAXParser parser = factory.newSAXParser();
      parser.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
      parser.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
      reader = parser.getXMLReader();
      reader.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      reader.setFeature("http://xml.org/sax/features/external-general-entities", false);
      reader.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
      reader.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
    } catch (ParserConfigurationException | SAXException e) {
      throw new IllegalStateException("the JDK SAX parser refused a setting", e);
    }
    reader.setErrorHandler(
        new DefaultHandler() {
          @Override
          public void fatalError(SAXParseException e) throws SAXException {
            throw e;
          }
        });
    reader.parse(source);
  }
}
