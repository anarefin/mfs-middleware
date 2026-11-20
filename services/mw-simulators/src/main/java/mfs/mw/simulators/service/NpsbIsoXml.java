package mfs.mw.simulators.service;

import java.io.StringReader;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/**
 * The ISO 20022 documents the NPSB switch mock reads and writes (ticket 121b part 2), with the JDK's DOM
 * (namespace aware, no DOCTYPE, no external entities), in the element order of the official XSDs
 * (mw-adapter-interop validates every one). Reads only the fields the mock needs; writes pacs.002.001.10,
 * camt.029.001.09 and, for the inbound push, pacs.008.001.08. No signature: the mock does not sign (open
 * item: the dev switch key pair).
 */
public final class NpsbIsoXml {

  public static final String PACS_008 = "urn:iso:std:iso:20022:tech:xsd:pacs.008.001.08";
  public static final String PACS_002 = "urn:iso:std:iso:20022:tech:xsd:pacs.002.001.10";
  public static final String PACS_028 = "urn:iso:std:iso:20022:tech:xsd:pacs.028.001.03";
  public static final String CAMT_056 = "urn:iso:std:iso:20022:tech:xsd:camt.056.001.08";
  public static final String CAMT_029 = "urn:iso:std:iso:20022:tech:xsd:camt.029.001.09";

  static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

  private NpsbIsoXml() {}

  /** What the mock reads of a pacs.008 (one transaction). */
  public record Transfer(String msgId, String endToEndId, String txId, long amountMinor, String currency,
      String debtorAgent, String creditorAgent, String creditorAccount) {}

  /** What the mock reads of a pacs.028 or a camt.056: the transfer it names. */
  public record Reference(String msgId, String endToEndId, String txId) {}

  public static Transfer readPacs008(String xml) {
    Document doc = parse(xml, PACS_008);
    Element tx = first(doc.getDocumentElement(), "CdtTrfTxInf");
    Element amt = first(tx, "IntrBkSttlmAmt");
    return new Transfer(
        text(first(doc.getDocumentElement(), "GrpHdr"), "MsgId"),
        text(tx, "EndToEndId"),
        text(tx, "TxId"),
        new BigDecimal(amt.getTextContent().trim()).movePointRight(2).longValueExact(),
        amt.getAttribute("Ccy"),
        text(first(tx, "DbtrAgt"), "BICFI"),
        text(first(tx, "CdtrAgt"), "BICFI"),
        text(first(first(tx, "CdtrAcct"), "Othr"), "Id"));
  }

  public static Reference readPacs028(String xml) {
    Document doc = parse(xml, PACS_028);
    Element tx = first(doc.getDocumentElement(), "TxInf");
    return new Reference(text(first(doc.getDocumentElement(), "GrpHdr"), "MsgId"),
        text(tx, "OrgnlEndToEndId"), text(tx, "OrgnlTxId"));
  }

  public static Reference readCamt056(String xml) {
    Document doc = parse(xml, CAMT_056);
    Element tx = first(first(doc.getDocumentElement(), "Undrlyg"), "TxInf");
    return new Reference(text(first(doc.getDocumentElement(), "Assgnmt"), "Id"),
        text(tx, "OrgnlEndToEndId"), text(tx, "OrgnlTxId"));
  }

  /** pacs.002.001.10, one transaction: {@code status} ACSP / ACSC / RJCT / PDNG, {@code reason} for RJCT. */
  public static String pacs002(String msgId, OffsetDateTime at, String originalMsgId, String endToEndId,
      String txId, String status, String reason, String clearingRef) {
    Document doc = newDocument();
    Element root = doc.createElementNS(PACS_002, "Document");
    doc.appendChild(root);
    Element body = child(root, "FIToFIPmtStsRpt");
    Element hdr = child(body, "GrpHdr");
    leaf(hdr, "MsgId", msgId);
    leaf(hdr, "CreDtTm", TS.format(at.withOffsetSameInstant(ZoneOffset.UTC)));
    Element tx = child(body, "TxInfAndSts");
    Element grp = child(tx, "OrgnlGrpInf");
    leaf(grp, "OrgnlMsgId", originalMsgId);
    leaf(grp, "OrgnlMsgNmId", "pacs.008.001.08");
    leaf(tx, "OrgnlEndToEndId", endToEndId);
    leaf(tx, "OrgnlTxId", txId);
    leaf(tx, "TxSts", status);
    if (reason != null) {
      leaf(child(child(tx, "StsRsnInf"), "Rsn"), "Cd", reason);
    }
    if (clearingRef != null) {
      leaf(tx, "ClrSysRef", clearingRef);
    }
    return write(doc);
  }

  /** camt.029.001.09 answering a camt.056: {@code confirmation} CNCL (returned) or RJCR (refused). */
  public static String camt029(String id, OffsetDateTime at, String caseId, String confirmation,
      String endToEndId, String txId, String assigner, String assignee) {
    Document doc = newDocument();
    Element root = doc.createElementNS(CAMT_029, "Document");
    doc.appendChild(root);
    Element body = child(root, "RsltnOfInvstgtn");
    Element assgnmt = child(body, "Assgnmt");
    leaf(assgnmt, "Id", id);
    leaf(child(child(assgnmt, "Assgnr"), "Agt"), "BICFI", assigner, "FinInstnId");
    leaf(child(child(assgnmt, "Assgne"), "Agt"), "BICFI", assignee, "FinInstnId");
    leaf(assgnmt, "CreDtTm", TS.format(at.withOffsetSameInstant(ZoneOffset.UTC)));
    Element rslvd = child(body, "RslvdCase");
    leaf(rslvd, "Id", caseId);
    leaf(child(child(rslvd, "Cretr"), "Agt"), "BICFI", assignee, "FinInstnId");
    leaf(child(body, "Sts"), "Conf", confirmation);
    Element tx = child(body, "CxlDtls");
    Element txInf = child(tx, "TxInfAndSts");
    leaf(txInf, "OrgnlEndToEndId", endToEndId);
    leaf(txInf, "OrgnlTxId", txId);
    return write(doc);
  }

  /** pacs.008.001.08 the mock pushes to the adapter (POST /mock-control/npsb/inbound). */
  public static String pacs008(String msgId, OffsetDateTime at, String endToEndId, String txId, long amountMinor,
      String debtorName, String debtorAccount, String debtorAgent, String creditorName, String creditorAccount,
      String creditorAgent) {
    Document doc = newDocument();
    Element root = doc.createElementNS(PACS_008, "Document");
    doc.appendChild(root);
    Element body = child(root, "FIToFICstmrCdtTrf");
    Element hdr = child(body, "GrpHdr");
    leaf(hdr, "MsgId", msgId);
    leaf(hdr, "CreDtTm", TS.format(at.withOffsetSameInstant(ZoneOffset.UTC)));
    leaf(hdr, "NbOfTxs", "1");
    leaf(child(hdr, "SttlmInf"), "SttlmMtd", "CLRG");
    Element tx = child(body, "CdtTrfTxInf");
    Element pmt = child(tx, "PmtId");
    leaf(pmt, "EndToEndId", endToEndId);
    leaf(pmt, "TxId", txId);
    Element amt = leaf(tx, "IntrBkSttlmAmt", BigDecimal.valueOf(amountMinor, 2).toPlainString());
    amt.setAttribute("Ccy", "BDT");
    leaf(tx, "IntrBkSttlmDt", at.atZoneSameInstant(java.time.ZoneId.of("Asia/Dhaka")).toLocalDate().toString());
    leaf(tx, "ChrgBr", "SLEV");
    leaf(child(tx, "Dbtr"), "Nm", debtorName);
    leaf(child(child(tx, "DbtrAcct"), "Id"), "Id", debtorAccount, "Othr");
    leaf(child(tx, "DbtrAgt"), "BICFI", debtorAgent, "FinInstnId");
    leaf(child(tx, "CdtrAgt"), "BICFI", creditorAgent, "FinInstnId");
    leaf(child(tx, "Cdtr"), "Nm", creditorName);
    leaf(child(child(tx, "CdtrAcct"), "Id"), "Id", creditorAccount, "Othr");
    return write(doc);
  }

  // ---- DOM helpers -----------------------------------------------------------------------------

  private static Document parse(String xml, String namespace) {
    try {
      DocumentBuilderFactory f = factory();
      Document doc = f.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
      Element root = doc.getDocumentElement();
      if (!"Document".equals(root.getLocalName()) || !namespace.equals(root.getNamespaceURI())) {
        throw new IllegalArgumentException("not a " + namespace + " Document");
      }
      return doc;
    } catch (IllegalArgumentException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalArgumentException("not well-formed or safe XML", e);
    }
  }

  private static DocumentBuilderFactory factory() throws Exception {
    DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
    f.setNamespaceAware(true);
    f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
    f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    f.setFeature("http://xml.org/sax/features/external-general-entities", false);
    f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
    f.setXIncludeAware(false);
    f.setExpandEntityReferences(false);
    return f;
  }

  private static Document newDocument() {
    try {
      return factory().newDocumentBuilder().newDocument();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static Element first(Element parent, String name) {
    if (parent == null) {
      throw new IllegalArgumentException("missing parent of " + name);
    }
    NodeList list = parent.getElementsByTagNameNS(parent.getNamespaceURI(), name);
    if (list.getLength() == 0) {
      throw new IllegalArgumentException("missing " + name);
    }
    return (Element) list.item(0);
  }

  private static String text(Element parent, String name) {
    return first(parent, name).getTextContent().trim();
  }

  private static Element child(Element parent, String name) {
    Element e = parent.getOwnerDocument().createElementNS(parent.getNamespaceURI(), name);
    parent.appendChild(e);
    return e;
  }

  private static Element leaf(Element parent, String name, String value) {
    Element e = child(parent, name);
    if (value != null) {
      e.setTextContent(value);
    }
    return e;
  }

  /** {@code <wrapper><name>value</name></wrapper>} under parent (FinInstnId/BICFI, Othr/Id). */
  private static Element leaf(Element parent, String name, String value, String wrapper) {
    return leaf(child(parent, wrapper), name, value);
  }

  private static String write(Document doc) {
    try {
      TransformerFactory tf = TransformerFactory.newInstance();
      tf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      var t = tf.newTransformer();
      t.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
      StringWriter out = new StringWriter();
      t.transform(new DOMSource(doc), new StreamResult(out));
      return out.toString();
    } catch (Exception e) {
      throw new IllegalStateException("ISO document not written", e);
    }
  }
}
