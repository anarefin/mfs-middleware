package mfs.mw.adapter.telco.partner.mapping;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.sax.SAXSource;
import net.sf.saxon.Configuration;
import net.sf.saxon.lib.Feature;
import net.sf.saxon.s9api.DOMDestination;
import net.sf.saxon.s9api.Processor;
import net.sf.saxon.s9api.QName;
import net.sf.saxon.s9api.SaxonApiException;
import net.sf.saxon.s9api.XPathSelector;
import net.sf.saxon.s9api.XdmAtomicValue;
import net.sf.saxon.s9api.XdmNode;
import net.sf.saxon.s9api.Xslt30Transformer;
import net.sf.saxon.s9api.XsltCompiler;
import net.sf.saxon.s9api.XsltExecutable;
import net.sf.saxon.s9api.XmlProcessingError;
import net.sf.saxon.trans.XPathException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;

/**
 * An XSLT stylesheet of a SOAP partner record (ticket 85, {@code mappings.<name>.xsl}), compiled
 * once per record version with Saxon-HE and shared by concurrent calls ({@link XsltExecutable} is
 * thread-safe; each call loads its own transformer).
 *
 * <p>Nothing reaches outside the stylesheet: {@link StylesheetPolicy} is checked first; the
 * configuration, the compiler and every transformer deny every resource ({@code doc()}, {@code
 * document()}, {@code xsl:include}, {@code unparsed-text()}, collections, {@code
 * xsl:result-document}) and external functions; the stylesheet and every document a partner
 * sends are parsed with DOCTYPE refused and external entities off (Saxon's own secure processing
 * does not refuse external DTDs). {@code xsl:message} and Saxon's diagnostics are dropped, never
 * printed. Camel's {@code xslt-saxon} component is not used: in Camel 4.22 its {@code
 * XsltUriResolver} still resolves {@code http:} / {@code file:} in {@code document()} at transform
 * time (fixed in 4.23), and it would compile per endpoint rather than per record version.
 *
 * <p>The two transforms {@code mw-soap-call} runs: {@link #requestElement} reads the request JSON
 * as its XPath 3.1 XML representation ({@code fn:json-to-xml}, namespace {@code
 * http://www.w3.org/2005/xpath-functions}) and makes the SOAP body element; {@link #responseJson}
 * reads the SOAP body element (or the fault document of {@link #faultJson}) and writes JSON (its
 * {@code xsl:output}, e.g. {@code method="json"}).
 */
public final class CompiledStylesheet {

  /** Mapping names ending in this are XSLT stylesheets, the others JSONata. */
  public static final String SUFFIX = ".xsl";

  /** The namespace of the document a SOAP fault is given to the response stylesheet as. */
  public static final String FAULT_NS = "urn:bits:mw:soap-fault";

  private static final Processor SAXON = processor();

  private final XsltExecutable executable;

  private CompiledStylesheet(XsltExecutable executable) {
    this.executable = executable;
  }

  /** True when mapping {@code name} is a stylesheet. */
  public static boolean isStylesheet(String name) {
    return name != null && name.endsWith(SUFFIX);
  }

  /**
   * @throws IllegalArgumentException when {@code source} breaks {@link StylesheetPolicy} or does
   *     not compile
   */
  public static CompiledStylesheet compile(String source) {
    List<String> problems = StylesheetPolicy.problems(source);
    if (!problems.isEmpty()) {
      throw new IllegalArgumentException(String.join("; ", problems));
    }
    XsltCompiler compiler = SAXON.newXsltCompiler();
    compiler.setResourceResolver(CompiledStylesheet::deny);
    List<XmlProcessingError> errors = new ArrayList<>();
    compiler.setErrorReporter(errors::add);
    try {
      return new CompiledStylesheet(compiler.compile(secureSource(source)));
    } catch (SaxonApiException e) {
      String reason = errors.stream().filter(x -> !x.isWarning()).map(XmlProcessingError::getMessage).findFirst()
          .orElse(e.getMessage());
      throw new IllegalArgumentException("the stylesheet does not compile: " + firstLine(reason), e);
    }
  }

  /** The SOAP body element the stylesheet makes of {@code json}. */
  public Element requestElement(String json) {
    try {
      XdmNode input = jsonToXml(json == null || json.isBlank() ? "{}" : json);
      Document out = documentBuilders().newDocumentBuilder().newDocument();
      Xslt30Transformer t = transformer();
      t.setGlobalContextItem(input);
      t.applyTemplates(input, new DOMDestination(out));
      Element root = out.getDocumentElement();
      if (root == null) {
        throw new IllegalArgumentException("the request stylesheet made no element");
      }
      return root;
    } catch (SaxonApiException | ParserConfigurationException | RuntimeException e) {
      throw failed(e);
    }
  }

  /** The JSON the stylesheet writes for the SOAP body element {@code xml}. */
  public String responseJson(String xml) {
    try {
      return apply(SAXON.newDocumentBuilder().build(secureSource(xml)));
    } catch (SaxonApiException | RuntimeException e) {
      throw failed(e);
    }
  }

  /**
   * The JSON the stylesheet writes for a SOAP fault, given as {@code <fault xmlns="urn:bits:mw:soap-fault"
   * code subcode reason>} holding the fault's {@code <detail>} (when there is one): {@code code} and
   * {@code subcode} are local names of the fault code QNames.
   */
  public String faultJson(String code, String subcode, String reason, String detailXml) {
    Element detail;
    try {
      detail = detailXml == null || detailXml.isBlank() ? null : StylesheetPolicy.parse(detailXml).getDocumentElement();
    } catch (RuntimeException e) {
      throw failed(e);
    }
    return faultJson(code, subcode, reason, detail);
  }

  /** As {@link #faultJson(String, String, String, String)}, with the fault's parsed detail element. */
  public String faultJson(String code, String subcode, String reason, Element detail) {
    try {
      Document doc = documentBuilders().newDocumentBuilder().newDocument();
      Element fault = doc.createElementNS(FAULT_NS, "fault");
      doc.appendChild(fault);
      fault.setAttribute("code", code == null ? "" : code);
      fault.setAttribute("subcode", subcode == null ? "" : subcode);
      fault.setAttribute("reason", reason == null ? "" : reason);
      if (detail != null) {
        Element wrapped = doc.createElementNS(FAULT_NS, "detail");
        for (Node child = detail.getFirstChild(); child != null; child = child.getNextSibling()) {
          wrapped.appendChild(doc.importNode(child, true));
        }
        fault.appendChild(wrapped);
      }
      return apply(SAXON.newDocumentBuilder().wrap(doc));
    } catch (SaxonApiException | ParserConfigurationException | RuntimeException e) {
      throw failed(e);
    }
  }

  private String apply(XdmNode input) throws SaxonApiException {
    StringWriter out = new StringWriter();
    Xslt30Transformer t = transformer();
    t.setGlobalContextItem(input);
    t.applyTemplates(input, t.newSerializer(out));
    return out.toString();
  }

  private Xslt30Transformer transformer() {
    Xslt30Transformer t = executable.load30();
    t.setResourceResolver(CompiledStylesheet::deny);
    t.setUnparsedTextResolver((uri, encoding, config, stable) -> {
      throw new XPathException("unparsed-text is not allowed");
    });
    t.setResultDocumentHandler(uri -> {
      throw new IllegalStateException("xsl:result-document is not allowed");
    });
    t.setMessageHandler(message -> {
      // xsl:message is dropped: it would go to stderr and may carry personal data
    });
    t.setErrorReporter(error -> {
      // dynamic errors surface as exceptions; warnings are dropped
    });
    return t;
  }

  private static XdmNode jsonToXml(String json) throws SaxonApiException {
    XPathSelector select = jsonToXml.load();
    select.setVariable(new QName("json"), new XdmAtomicValue(json));
    return (XdmNode) select.evaluateSingle();
  }

  private static final net.sf.saxon.s9api.XPathExecutable jsonToXml = compileJsonToXml();

  private static net.sf.saxon.s9api.XPathExecutable compileJsonToXml() {
    try {
      var xpath = SAXON.newXPathCompiler();
      xpath.declareVariable(new QName("json"));
      return xpath.compile("json-to-xml($json)");
    } catch (SaxonApiException e) {
      throw new IllegalStateException(e);
    }
  }

  private static javax.xml.transform.Source deny(net.sf.saxon.lib.ResourceRequest request) throws XPathException {
    throw new XPathException("a partner stylesheet may not read " + request.uri);
  }

  /** {@code xml} as a source whose parser refuses DOCTYPE and external entities. */
  static SAXSource secureSource(String xml) {
    try {
      javax.xml.parsers.SAXParserFactory f = javax.xml.parsers.SAXParserFactory.newInstance();
      f.setNamespaceAware(true);
      f.setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true);
      f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      f.setFeature("http://xml.org/sax/features/external-general-entities", false);
      f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
      f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
      f.setXIncludeAware(false);
      XMLReader reader = f.newSAXParser().getXMLReader();
      reader.setEntityResolver((publicId, systemId) -> {
        throw new SAXException("external entities are not allowed");
      });
      reader.setErrorHandler(StylesheetPolicy.SILENT);
      return new SAXSource(reader, new InputSource(new StringReader(xml)));
    } catch (ParserConfigurationException | SAXException e) {
      throw new IllegalStateException("no secure XML parser", e);
    }
  }

  private static DocumentBuilderFactory documentBuilders() throws ParserConfigurationException {
    return StylesheetPolicy.secureFactory();
  }

  private static Processor processor() {
    Processor p = new Processor(false);
    Configuration c = p.getUnderlyingConfiguration();
    c.setResourceResolver(CompiledStylesheet::deny);
    c.setUnparsedTextURIResolver((uri, encoding, config, stable) -> {
      throw new XPathException("unparsed-text is not allowed");
    });
    c.setCollectionFinder((context, uri) -> {
      throw new XPathException("collections are not allowed");
    });
    p.setConfigurationProperty(Feature.ALLOW_EXTERNAL_FUNCTIONS, false);
    p.setConfigurationProperty(Feature.XINCLUDE, false);
    p.setConfigurationProperty(Feature.DTD_VALIDATION, false);
    return p;
  }

  private static IllegalArgumentException failed(Exception e) {
    if (e instanceof IllegalArgumentException iae) {
      return iae;
    }
    return new IllegalArgumentException("the stylesheet failed: " + firstLine(e.getMessage()), e);
  }

  private static String firstLine(String s) {
    if (s == null) {
      return "";
    }
    int nl = s.indexOf('\n');
    return nl < 0 ? s : s.substring(0, nl);
  }
}
