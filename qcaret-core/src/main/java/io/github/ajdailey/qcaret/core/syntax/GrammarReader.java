package io.github.ajdailey.qcaret.core.syntax;

import io.github.ajdailey.qcaret.core.concurrent.WorkerThread;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

/**
 * Reads a grammar file: the JDK parser with DOCTYPE declarations refused and external entity, DTD
 * and schema access off, validated against {@code grammar.xsd} while it parses. Then every regex
 * compiles and every context a rule names exists in the file. Thread-safe.
 */
public final class GrammarReader {
  /** The schema resource, beside this class. */
  public static final String SCHEMA = "grammar.xsd";

  /** Fails the parse on an error; a warning does not make a grammar invalid. */
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

  private final Schema schema;

  /**
   * Loads the schema.
   *
   * @throws IllegalStateException when the shipped schema does not load
   */
  public GrammarReader() {
    URL url = GrammarReader.class.getResource(SCHEMA);
    if (url == null) {
      throw new IllegalStateException("grammar schema missing");
    }
    try {
      SchemaFactory factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
      factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
      factory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
      schema = factory.newSchema(url);
    } catch (SAXException e) {
      throw new IllegalStateException("grammar schema did not load", e);
    }
  }

  /**
   * Reads one grammar.
   *
   * @param in the file's bytes; not closed
   * @param source names the file in messages
   * @return the grammar
   * @throws IOException when the stream fails
   * @throws InvalidGrammarException when the file is not a valid grammar
   */
  @WorkerThread
  public Grammar read(InputStream in, String source) throws IOException, InvalidGrammarException {
    Document document;
    try {
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
      builder.setErrorHandler(ERRORS);
      document = builder.parse(in);
    } catch (ParserConfigurationException e) {
      throw new InvalidGrammarException(source + ": parser setup failed", e);
    } catch (SAXParseException e) {
      throw new InvalidGrammarException(
          source + ":" + e.getLineNumber() + ": " + e.getMessage(), e);
    } catch (SAXException e) {
      throw new InvalidGrammarException(source + ": " + e.getMessage(), e);
    }
    try {
      return build(document.getDocumentElement());
    } catch (PatternSyntaxException e) {
      throw new InvalidGrammarException(
          source + ": invalid regex " + e.getPattern() + ": " + e.getDescription(), e);
    } catch (IllegalArgumentException e) {
      throw new InvalidGrammarException(source + ": " + e.getMessage(), e);
    }
  }

  private static Grammar build(Element root) {
    String id = root.getAttribute("id");
    List<String> extensions = new ArrayList<String>();
    List<String> fileNames = new ArrayList<String>();
    Pattern firstLine = null;
    Grammar.Comments comments = Grammar.Comments.NONE;
    List<String[]> brackets = new ArrayList<String[]>();
    List<String[]> autoClose = new ArrayList<String[]>();
    Pattern increase = null;
    Pattern decrease = null;
    Pattern dedentNext = null;
    Grammar.Folding folding = Grammar.Folding.NONE;
    Map<String, List<Grammar.Rule>> contexts = new LinkedHashMap<String, List<Grammar.Rule>>();
    for (Element e : children(root)) {
      switch (e.getTagName()) {
        case "detect" -> {
          for (Element d : children(e)) {
            String text = d.getTextContent().strip();
            switch (d.getTagName()) {
              case "extension" -> extensions.add(GrammarSet.asciiLower(text));
              case "fileName" -> fileNames.add(text);
              default -> firstLine = Pattern.compile(text);
            }
          }
        }
        case "comments" ->
            comments =
                new Grammar.Comments(
                    attribute(e, "line"), attribute(e, "blockStart"), attribute(e, "blockEnd"));
        case "brackets" -> pairs(e, brackets);
        case "autoClose" -> pairs(e, autoClose);
        case "indent" -> {
          increase = regex(e, "increase");
          decrease = regex(e, "decrease");
          dedentNext = regex(e, "dedentNext");
        }
        case "folding" ->
            folding =
                new Grammar.Folding(
                    switch (e.getAttribute("mode")) {
                      case "brackets" -> Grammar.FoldMode.BRACKETS;
                      case "markers" -> Grammar.FoldMode.MARKERS;
                      case "indent" -> Grammar.FoldMode.INDENT;
                      default -> Grammar.FoldMode.NONE;
                    },
                    e.getAttribute("open"),
                    e.getAttribute("close"),
                    regex(e, "begin"),
                    regex(e, "end"));
        default -> contexts.put(e.getAttribute("id"), rules(e));
      }
    }
    if (folding.open().length() != folding.close().length()) {
      throw new IllegalArgumentException("folding open and close differ in length");
    }
    if (folding.mode() == Grammar.FoldMode.MARKERS
        && (folding.begin() == null || folding.end() == null)) {
      throw new IllegalArgumentException("marker folding needs begin and end");
    }
    checkReferences(contexts);
    return new Grammar(
        id,
        root.getAttribute("name"),
        root.getAttribute("version"),
        attribute(root, "category"),
        extensions,
        fileNames,
        firstLine,
        comments,
        brackets,
        autoClose,
        increase,
        decrease,
        dedentNext,
        folding,
        contexts);
  }

  private static List<Grammar.Rule> rules(Element context) {
    List<Grammar.Rule> rules = new ArrayList<Grammar.Rule>();
    for (Element r : children(context)) {
      switch (r.getTagName()) {
        case "match" ->
            rules.add(
                new Grammar.Match(
                    regex(r, "regex"),
                    token(r),
                    attribute(r, "push"),
                    Boolean.parseBoolean(r.getAttribute("pop"))));
        case "keywords" -> {
          List<String> words = new ArrayList<String>();
          for (String word : r.getTextContent().strip().split("\\s+")) {
            if (!word.isEmpty()) {
              words.add(word);
            }
          }
          rules.add(
              new Grammar.Keywords(
                  words,
                  token(r),
                  Boolean.parseBoolean(r.getAttribute("ignoreCase")),
                  r.getAttribute("wordChars")));
        }
        case "span" -> {
          Grammar.Span span =
              new Grammar.Span(
                  regex(r, "begin"),
                  regex(r, "end"),
                  regex(r, "escape"),
                  token(r),
                  attribute(r, "context"),
                  Boolean.parseBoolean(r.getAttribute("multiLine")),
                  Boolean.parseBoolean(r.getAttribute("nested")),
                  Boolean.parseBoolean(r.getAttribute("bodyNextLine")));
          if (span.nested() && span.bodyNextLine()) {
            throw new IllegalArgumentException(
                "span " + span.begin() + " is nested and bodyNextLine");
          }
          if ((Syntax.templated(span.end()) || Syntax.templated(span.escape()))
              && span.begin().matcher("").groupCount() == 0) {
            throw new IllegalArgumentException(
                "span end or escape uses \\1 but begin " + span.begin() + " has no group");
          }
          rules.add(span);
        }
        case "include" -> rules.add(new Grammar.Include(r.getAttribute("context")));
        default ->
            rules.add(
                new Grammar.Embed(
                    r.getAttribute("grammar"), regex(r, "begin"), regex(r, "end"), token(r)));
      }
    }
    return rules;
  }

  /** Checks that every context a rule names exists. */
  private static void checkReferences(Map<String, List<Grammar.Rule>> contexts) {
    for (Map.Entry<String, List<Grammar.Rule>> entry : contexts.entrySet()) {
      for (Grammar.Rule rule : entry.getValue()) {
        String target = null;
        if (rule instanceof Grammar.Match m) {
          target = m.push();
        } else if (rule instanceof Grammar.Span s) {
          target = s.context();
        } else if (rule instanceof Grammar.Include i) {
          target = i.context();
        }
        if (target != null && !contexts.containsKey(target)) {
          throw new IllegalArgumentException(
              "context " + entry.getKey() + " names unknown context " + target);
        }
      }
    }
  }

  private static void pairs(Element e, List<String[]> out) {
    for (Element pair : children(e)) {
      out.add(new String[] {pair.getAttribute("open"), pair.getAttribute("close")});
    }
  }

  private static TokenType token(Element e) {
    String name = attribute(e, "token");
    return name == null ? null : TokenType.fromName(name);
  }

  private static Pattern regex(Element e, String name) {
    String value = attribute(e, name);
    return value == null ? null : Pattern.compile(value);
  }

  /** Returns an attribute's value, or null when absent or empty. */
  private static String attribute(Element e, String name) {
    String value = e.getAttribute(name);
    return value.isEmpty() ? null : value;
  }

  private static List<Element> children(Element parent) {
    List<Element> out = new ArrayList<Element>();
    NodeList nodes = parent.getChildNodes();
    for (int i = 0; i < nodes.getLength(); i++) {
      Node node = nodes.item(i);
      if (node.getNodeType() == Node.ELEMENT_NODE) {
        out.add((Element) node);
      }
    }
    return out;
  }
}
