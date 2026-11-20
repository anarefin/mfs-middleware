package mfs.mw.adapter.card.partner.mapping;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

/**
 * What an XSLT stylesheet of a partner record may hold (ticket 85, ADR D28 guardrail 2): it works
 * on the document it is given and nothing else. Refused: a DOCTYPE or entity (XXE), {@code
 * xsl:import} / {@code xsl:include} / {@code xsl:use-package}, {@code xsl:result-document}, {@code
 * xsl:source-document}, {@code xsl:merge-source}, {@code xsl:evaluate}, extension elements and
 * instructions or functions of another namespace than XSLT's and the result's (Saxon, {@code
 * java:}), and the functions that read a resource or call code by name ({@code doc}, {@code
 * document}, {@code unparsed-text*}, {@code collection}, {@code uri-collection}, {@code json-doc},
 * {@code transform}, {@code load-xquery-module}, {@code function-lookup}, {@code
 * environment-variable}, {@code parse-xml}, {@code parse-xml-fragment}, {@code json-to-xml}, ...),
 * wherever they appear (attributes, text value templates), a DOCTYPE or entity declaration even in
 * a decoded value, and what builds an expression at compile time out of sight of this check:
 * shadow attributes ({@code _select}) and static parameters and variables. Plain JDK
 * XML only, so mw-admin-api keeps a copy of it (D14) and the maker sees the same refusals; the
 * adapter also compiles the stylesheet with every resolver denied ({@link CompiledStylesheet}).
 */
public final class StylesheetPolicy {

  public static final String XSLT_NS = "http://www.w3.org/1999/XSL/Transform";

  /** The largest stylesheet a record may hold. */
  public static final int MAX_LENGTH = 64 * 1024;

  /** The {@code xsl:*} elements of the source that reach outside the stylesheet (local names). */
  static final Set<String> FORBIDDEN_XSL_ELEMENTS = Set.of(
      "import", "include", "use-package", "package", "result-document", "source-document", "merge-source",
      "evaluate", "original");

  /**
   * The functions a partner stylesheet may not call: those that read a resource, parse a string
   * as XML at run time (a DTD or external entity there would be fetched), or call code by name.
   */
  public static final Set<String> FORBIDDEN_FUNCTIONS = Set.of(
      "doc", "document", "doc-available", "unparsed-text", "unparsed-text-lines", "unparsed-text-available",
      "collection", "uri-collection", "json-doc", "parse-xml", "parse-xml-fragment", "json-to-xml", "transform",
      "load-xquery-module", "function-lookup", "environment-variable", "available-environment-variables");

  static final Pattern FORBIDDEN_FUNCTION = Pattern.compile(
      "(?<![\\w.\\-])(" + String.join("|", FORBIDDEN_FUNCTIONS.stream().sorted((a, b) -> b.length() - a.length()).toList())
          + ")\\s*[(#]");

  private static final Pattern DECLARATION = Pattern.compile("(?i)<!\\s*(DOCTYPE|ENTITY)");

  /** Throws on errors and drops warnings: the JDK's default handler would print to stderr. */
  static final org.xml.sax.ErrorHandler SILENT = new org.xml.sax.ErrorHandler() {
    @Override
    public void warning(org.xml.sax.SAXParseException e) {
      // dropped
    }

    @Override
    public void error(org.xml.sax.SAXParseException e) throws SAXException {
      throw e;
    }

    @Override
    public void fatalError(org.xml.sax.SAXParseException e) throws SAXException {
      throw e;
    }
  };

  private StylesheetPolicy() {}

  /** Every reason {@code source} may not be used as a partner stylesheet; empty when none. */
  public static List<String> problems(String source) {
    List<String> problems = new ArrayList<>();
    if (source == null || source.isBlank()) {
      problems.add("the stylesheet is empty");
      return problems;
    }
    if (source.length() > MAX_LENGTH) {
      problems.add("the stylesheet is longer than " + MAX_LENGTH + " characters");
      return problems;
    }
    if (DECLARATION.matcher(source).find()) {
      problems.add("a stylesheet may not declare a DOCTYPE or entities (external entities reach outside it)");
      return problems;
    }
    Document doc;
    try {
      doc = parse(source);
    } catch (IllegalArgumentException e) {
      problems.add("the stylesheet is not well-formed XML: " + e.getMessage());
      return problems;
    }
    Element root = doc.getDocumentElement();
    if (!XSLT_NS.equals(root.getNamespaceURI())
        || !Set.of("stylesheet", "transform").contains(root.getLocalName())) {
      problems.add("the stylesheet must be one xsl:stylesheet (or xsl:transform)");
      return problems;
    }
    Set<String> found = new LinkedHashSet<>();
    walk(root, found);
    problems.addAll(found);
    return problems;
  }

  private static void walk(Node node, Set<String> problems) {
    if (node instanceof Element e) {
      String ns = e.getNamespaceURI();
      if (XSLT_NS.equals(ns) && FORBIDDEN_XSL_ELEMENTS.contains(e.getLocalName())) {
        problems.add("xsl:" + e.getLocalName() + " is not allowed in a partner stylesheet (it reaches outside the stylesheet)");
      }
      foreignNamespace(ns, problems);
      NamedNodeMap attributes = e.getAttributes();
      for (int i = 0; i < attributes.getLength(); i++) {
        Attr a = (Attr) attributes.item(i);
        String name = a.getLocalName() == null ? a.getName() : a.getLocalName();
        if (XMLConstants.XMLNS_ATTRIBUTE_NS_URI.equals(a.getNamespaceURI())) {
          foreignNamespace(a.getValue(), problems);
          continue;
        }
        if (name.equals("extension-element-prefixes")) {
          problems.add("extension elements are not allowed in a partner stylesheet");
        }
        if (name.startsWith("_")) {
          // a shadow attribute: its value builds the attribute at compile time, out of sight of this check
          problems.add("shadow attributes (" + name + ") are not allowed in a partner stylesheet");
        }
        if (XSLT_NS.equals(ns) && name.equals("static") && !"no".equals(a.getValue().trim())
            && !"false".equals(a.getValue().trim()) && !"0".equals(a.getValue().trim())) {
          problems.add("static parameters and variables are not allowed in a partner stylesheet");
        }
        functions(a.getValue(), problems);
        declarations(a.getValue(), problems);
      }
    } else if (node.getNodeType() == Node.TEXT_NODE || node.getNodeType() == Node.CDATA_SECTION_NODE) {
      functions(node.getNodeValue(), problems);
      declarations(node.getNodeValue(), problems);
    } else if (node.getNodeType() == Node.PROCESSING_INSTRUCTION_NODE) {
      problems.add("processing instructions are not allowed in a partner stylesheet");
    }
    for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
      walk(child, problems);
    }
  }

  private static void foreignNamespace(String ns, Set<String> problems) {
    if (ns == null) {
      return;
    }
    String lower = ns.toLowerCase(Locale.ROOT);
    if (lower.startsWith("java:") || lower.startsWith("http://saxon.sf.net") || lower.startsWith("http://exslt.org")
        || lower.startsWith("http://xml.apache.org/x")) {
      problems.add("extension namespaces (" + ns + ") are not allowed in a partner stylesheet");
    }
  }

  /** A DOCTYPE or entity declaration in a decoded value (it would reach a run-time parser). */
  private static void declarations(String text, Set<String> problems) {
    if (text != null && DECLARATION.matcher(text).find()) {
      problems.add("a stylesheet may not hold a DOCTYPE or entity declaration, not even in a value");
    }
  }

  private static void functions(String text, Set<String> problems) {
    if (text == null) {
      return;
    }
    Matcher m = FORBIDDEN_FUNCTION.matcher(text);
    while (m.find()) {
      problems.add(m.group(1) + "() is not allowed in a partner stylesheet (it reads a resource or calls code by name)");
    }
  }

  // --- the one secure XML parser setup (DOM here, SAX for Saxon) ---------------------------------

  /**
   * The parser features of every XML parse of a partner stylesheet or document, DOM or SAX:
   * secure processing on, a DOCTYPE refused, no external entity or DTD loaded.
   */
  static final java.util.Map<String, Boolean> SECURE_FEATURES = java.util.Map.of(
      XMLConstants.FEATURE_SECURE_PROCESSING, true,
      "http://apache.org/xml/features/disallow-doctype-decl", true,
      "http://xml.org/sax/features/external-general-entities", false,
      "http://xml.org/sax/features/external-parameter-entities", false,
      "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);

  /** Refuses every external entity a parser would resolve. */
  static final org.xml.sax.EntityResolver NO_ENTITIES = (publicId, systemId) -> {
    throw new SAXException("external entities are not allowed");
  };

  /** Parses {@code xml} with every external access off and DOCTYPE refused. */
  static Document parse(String xml) {
    try {
      DocumentBuilder builder = secureFactory().newDocumentBuilder();
      builder.setEntityResolver(NO_ENTITIES);
      builder.setErrorHandler(SILENT);
      return builder.parse(new InputSource(new StringReader(xml)));
    } catch (ParserConfigurationException | SAXException | IOException e) {
      throw new IllegalArgumentException(firstLine(e.getMessage()), e);
    }
  }

  /** A new empty DOM document from the secure factory. */
  public static Document secureDocument() throws ParserConfigurationException {
    return secureFactory().newDocumentBuilder().newDocument();
  }

  static DocumentBuilderFactory secureFactory() throws ParserConfigurationException {
    DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
    f.setNamespaceAware(true);
    for (java.util.Map.Entry<String, Boolean> feature : SECURE_FEATURES.entrySet()) {
      f.setFeature(feature.getKey(), feature.getValue());
    }
    f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
    f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
    f.setXIncludeAware(false);
    f.setExpandEntityReferences(false);
    return f;
  }

  /**
   * A SAX reader with the same setup ({@link #SECURE_FEATURES}, {@link #NO_ENTITIES}, {@link
   * #SILENT}): the parser Saxon gets for every document it reads, at compile and at run time.
   */
  static org.xml.sax.XMLReader secureReader() {
    try {
      javax.xml.parsers.SAXParserFactory f = javax.xml.parsers.SAXParserFactory.newInstance();
      f.setNamespaceAware(true);
      for (java.util.Map.Entry<String, Boolean> feature : SECURE_FEATURES.entrySet()) {
        f.setFeature(feature.getKey(), feature.getValue());
      }
      f.setXIncludeAware(false);
      org.xml.sax.XMLReader reader = f.newSAXParser().getXMLReader();
      reader.setEntityResolver(NO_ENTITIES);
      reader.setErrorHandler(SILENT);
      return reader;
    } catch (ParserConfigurationException | SAXException e) {
      throw new IllegalStateException("no secure XML parser", e);
    }
  }

  private static String firstLine(String s) {
    if (s == null) {
      return "";
    }
    int nl = s.indexOf('\n');
    return nl < 0 ? s : s.substring(0, nl);
  }
}
