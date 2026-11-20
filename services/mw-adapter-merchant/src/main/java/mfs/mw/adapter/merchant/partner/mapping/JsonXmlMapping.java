package mfs.mw.adapter.merchant.partner.mapping;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * JSONata to XML and back for {@code mw-soap-call} (review of ticket 85), next to XSLT: with {@code
 * request=json} the JSON {@code mw-map}'s JSONata mapping made is written as the SOAP body element,
 * with {@code response=json} the SOAP body (or fault) is read back into JSON for {@code
 * mw-classify}'s JSONata mapping, both by one fixed convention instead of a stylesheet:
 *
 * <ul>
 *   <li>the JSON is one object with one key, the body element; every element is in the call's
 *       {@code namespace} (an {@code urn:} or http(s) URI), unprefixed;
 *   <li>a key is a child element (an XML name without a prefix), {@code @name} an attribute (a
 *       value), {@code #text} the element's text; an array repeats the element; a string, number or
 *       boolean is the element's text, null an empty element;
 *   <li>back: an element is its local name (namespaces dropped); one with only text is the string,
 *       else an object of {@code @attributes}, child elements (repeated ones as an array) and {@code
 *       #text}; a fault is {@code {"fault": {"@code", "@subcode", "@reason", "detail"}}}.
 * </ul>
 *
 * <p>The DOM builds the XML, so a value is always text, never markup; parsing uses the policy's
 * secure parser (no DOCTYPE, no entity). At most {@value #MAX_DEPTH} levels and {@value #MAX_NODES}
 * nodes either way. No partner data is put in an error message.
 */
public final class JsonXmlMapping implements SoapMapping {

  /** The {@code request} / {@code response} value of {@code mw-soap-call} that selects this mapping. */
  public static final String NAME = "json";

  static final int MAX_DEPTH = 32;
  static final int MAX_NODES = 10_000;
  private static final Pattern XML_NAME = Pattern.compile("^[A-Za-z_][A-Za-z0-9._-]{0,63}$");
  private static final Pattern NAMESPACE = Pattern.compile(
      "^(urn:[A-Za-z0-9][A-Za-z0-9._:-]{0,199}|https?://[A-Za-z0-9.-]{1,253}(:[0-9]{1,5})?(/[A-Za-z0-9._~-]*){0,20})$");

  private final String namespace;
  private final JsonMapper json;

  public JsonXmlMapping(String namespace, JsonMapper json) {
    if (!validNamespace(namespace)) {
      throw new IllegalArgumentException("the SOAP namespace must be an urn: or http(s) URI");
    }
    this.namespace = namespace;
    this.json = json;
  }

  /** An {@code urn:} or http(s) URI of letters, digits and {@code . _ ~ : / -} only. */
  public static boolean validNamespace(String namespace) {
    return namespace != null && NAMESPACE.matcher(namespace).matches();
  }

  @Override
  public Element requestElement(String text) {
    JsonNode root;
    try {
      root = json.readTree(text == null || text.isBlank() ? "{}" : text);
    } catch (JacksonException e) {
      throw new IllegalArgumentException("the request is not JSON");
    }
    if (root == null || !root.isObject() || root.size() != 1) {
      throw new IllegalArgumentException("the request JSON must be one object with one key, the SOAP body element");
    }
    Map.Entry<String, JsonNode> body = root.properties().iterator().next();
    if (body.getValue().isArray()) {
      throw new IllegalArgumentException("the SOAP body element cannot be an array");
    }
    try {
      Document doc = StylesheetPolicy.secureDocument();
      int[] nodes = {0};
      Element element = element(doc, body.getKey(), body.getValue(), 1, nodes);
      doc.appendChild(element);
      return element;
    } catch (ParserConfigurationException e) {
      throw new IllegalStateException("no secure XML parser", e);
    }
  }

  private Element element(Document doc, String name, JsonNode value, int depth, int[] nodes) {
    if (depth > MAX_DEPTH) {
      throw new IllegalArgumentException("the request JSON is more than " + MAX_DEPTH + " levels deep");
    }
    if (++nodes[0] > MAX_NODES) {
      throw new IllegalArgumentException("the request JSON has more than " + MAX_NODES + " elements");
    }
    if (!XML_NAME.matcher(name).matches()) {
      throw new IllegalArgumentException("a request JSON key is not an XML name without a prefix");
    }
    Element e = doc.createElementNS(namespace, name);
    if (value.isObject()) {
      for (Map.Entry<String, JsonNode> field : value.properties()) {
        String key = field.getKey();
        JsonNode v = field.getValue();
        if (key.startsWith("@")) {
          if (!XML_NAME.matcher(key.substring(1)).matches() || !scalar(v)) {
            throw new IllegalArgumentException("a request JSON attribute must be an XML name with a value");
          }
          e.setAttribute(key.substring(1), text(v));
        } else if (key.equals("#text")) {
          if (!scalar(v)) {
            throw new IllegalArgumentException("#text must be a value");
          }
          e.appendChild(doc.createTextNode(text(v)));
        } else if (v.isArray()) {
          for (JsonNode item : v.values()) {
            if (item.isArray()) {
              throw new IllegalArgumentException("an array in the request JSON cannot hold an array");
            }
            e.appendChild(element(doc, key, item, depth + 1, nodes));
          }
        } else {
          e.appendChild(element(doc, key, v, depth + 1, nodes));
        }
      }
    } else if (!value.isNull()) {
      e.setTextContent(text(value));
    }
    return e;
  }

  private static boolean scalar(JsonNode v) {
    return v.isValueNode() && !v.isNull();
  }

  private static String text(JsonNode v) {
    if (v.isIntegralNumber()) {
      return v.bigIntegerValue().toString();
    }
    if (v.isNumber()) {
      return v.decimalValue().toPlainString();
    }
    return v.asString();
  }

  @Override
  public String responseJson(String xml) {
    Element root = StylesheetPolicy.parse(xml).getDocumentElement();
    Map<String, Object> out = new LinkedHashMap<>();
    out.put(local(root), value(root, 1, new int[] {0}));
    return json.writeValueAsString(out);
  }

  @Override
  public String faultJson(String code, String subcode, String reason, String detailXml) {
    Element detail = detailXml == null || detailXml.isBlank() ? null : StylesheetPolicy.parse(detailXml).getDocumentElement();
    return faultJson(code, subcode, reason, detail);
  }

  @Override
  public String faultJson(String code, String subcode, String reason, Element detail) {
    Map<String, Object> fault = new LinkedHashMap<>();
    fault.put("@code", code == null ? "" : code);
    fault.put("@subcode", subcode == null ? "" : subcode);
    fault.put("@reason", reason == null ? "" : reason);
    if (detail != null) {
      fault.put("detail", value(detail, 1, new int[] {0}));
    }
    return json.writeValueAsString(Map.of("fault", fault));
  }

  private static Object value(Element e, int depth, int[] nodes) {
    if (depth > MAX_DEPTH || ++nodes[0] > MAX_NODES) {
      throw new IllegalArgumentException("the SOAP answer is too deep or too large to read as JSON");
    }
    Map<String, Object> out = new LinkedHashMap<>();
    NamedNodeMap attributes = e.getAttributes();
    for (int i = 0; i < attributes.getLength(); i++) {
      Attr a = (Attr) attributes.item(i);
      if (!XMLConstants.XMLNS_ATTRIBUTE_NS_URI.equals(a.getNamespaceURI())) {
        out.put("@" + (a.getLocalName() == null ? a.getName() : a.getLocalName()), a.getValue());
      }
    }
    StringBuilder text = new StringBuilder();
    boolean children = false;
    for (Node child = e.getFirstChild(); child != null; child = child.getNextSibling()) {
      if (child instanceof Element c) {
        children = true;
        String name = local(c);
        Object v = value(c, depth + 1, nodes);
        Object held = out.get(name);
        if (held == null) {
          out.put(name, v);
        } else if (held instanceof Repeated list) {
          list.add(v);
        } else {
          Repeated list = new Repeated();
          list.add(held);
          list.add(v);
          out.put(name, list);
        }
      } else if (child.getNodeType() == Node.TEXT_NODE || child.getNodeType() == Node.CDATA_SECTION_NODE) {
        text.append(child.getNodeValue());
      }
    }
    String t = children ? text.toString().strip() : text.toString();
    if (out.isEmpty()) {
      return t;
    }
    if (!t.isEmpty()) {
      out.put("#text", t);
    }
    return out;
  }

  private static String local(Element e) {
    return e.getLocalName() == null ? e.getTagName() : e.getLocalName();
  }

  /** The values of a repeated element (an array in the JSON). */
  private static final class Repeated extends ArrayList<Object> {
    private static final long serialVersionUID = 1L;
  }
}
