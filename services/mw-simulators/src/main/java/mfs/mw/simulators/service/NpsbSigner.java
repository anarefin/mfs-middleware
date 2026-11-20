package mfs.mw.simulators.service;

import java.io.StringReader;
import java.io.StringWriter;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.crypto.dsig.CanonicalizationMethod;
import javax.xml.crypto.dsig.DigestMethod;
import javax.xml.crypto.dsig.Reference;
import javax.xml.crypto.dsig.SignatureMethod;
import javax.xml.crypto.dsig.SignedInfo;
import javax.xml.crypto.dsig.Transform;
import javax.xml.crypto.dsig.XMLSignatureFactory;
import javax.xml.crypto.dsig.dom.DOMSignContext;
import javax.xml.crypto.dsig.keyinfo.KeyInfoFactory;
import javax.xml.crypto.dsig.spec.C14NMethodParameterSpec;
import javax.xml.crypto.dsig.spec.TransformParameterSpec;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;

/**
 * The NPSB switch mock's XML-DSig (ticket 121b part 2): every pacs.002 / camt.029 answer and every
 * inbound pacs.008 push is signed with the dev switch key, in the one profile mw-crypto verifies
 * (enveloped signature over the whole document, exclusive C14N, SHA-256, RSA-SHA256, {@code
 * KeyName npsb-switch}). The key is {@code sim.npsb.signing-key} (PKCS#8 PEM, Vault secret/mw-simulators;
 * provision creates the pair once and gives mw-adapter-interop the certificate as {@code
 * npsb-certificate-pem}). Without a key the documents go out unsigned (unit tests, a stack without Vault).
 */
@Component
public class NpsbSigner {

  static final String KEY_NAME = "npsb-switch";

  private final XMLSignatureFactory factory = XMLSignatureFactory.getInstance("DOM");
  private final PrivateKey key;

  public NpsbSigner(@Value("${sim.npsb.signing-key:}") String pem) {
    this.key = pem == null || pem.isBlank() ? null : privateKey(pem);
  }

  public boolean enabled() {
    return key != null;
  }

  /** {@code xml} with an enveloped signature, or unchanged when no key is configured. */
  public String sign(String xml) {
    if (key == null) {
      return xml;
    }
    try {
      Document doc = parse(xml);
      Reference ref = factory.newReference("", factory.newDigestMethod(DigestMethod.SHA256, null),
          List.of(factory.newTransform(Transform.ENVELOPED, (TransformParameterSpec) null),
              factory.newTransform(CanonicalizationMethod.EXCLUSIVE, (TransformParameterSpec) null)),
          null, null);
      SignedInfo si = factory.newSignedInfo(
          factory.newCanonicalizationMethod(CanonicalizationMethod.EXCLUSIVE, (C14NMethodParameterSpec) null),
          factory.newSignatureMethod(SignatureMethod.RSA_SHA256, null), List.of(ref));
      KeyInfoFactory kif = factory.getKeyInfoFactory();
      DOMSignContext ctx = new DOMSignContext(key, doc.getDocumentElement());
      ctx.setDefaultNamespacePrefix("ds");
      factory.newXMLSignature(si, kif.newKeyInfo(List.of(kif.newKeyName(KEY_NAME)))).sign(ctx);
      return write(doc);
    } catch (Exception e) {
      throw new IllegalStateException("cannot sign the NPSB document", e);
    }
  }

  private static PrivateKey privateKey(String pem) {
    String b64 = pem.replaceAll("-----BEGIN [A-Z ]+-----", "").replaceAll("-----END [A-Z ]+-----", "")
        .replaceAll("\\s", "");
    try {
      return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(b64)));
    } catch (Exception e) {
      throw new IllegalStateException("sim.npsb.signing-key is not a PKCS#8 RSA private key");
    }
  }

  private static Document parse(String xml) throws Exception {
    DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
    f.setNamespaceAware(true);
    f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
    f.setXIncludeAware(false);
    f.setExpandEntityReferences(false);
    return f.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
  }

  private static String write(Document doc) throws Exception {
    TransformerFactory tf = TransformerFactory.newInstance();
    tf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
    var t = tf.newTransformer();
    t.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
    StringWriter out = new StringWriter();
    t.transform(new DOMSource(doc), new StreamResult(out));
    return out.toString();
  }
}
