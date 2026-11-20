package mfs.mw.simulators.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import javax.xml.crypto.dsig.CanonicalizationMethod;
import javax.xml.crypto.dsig.DigestMethod;
import javax.xml.crypto.dsig.SignatureMethod;
import javax.xml.crypto.dsig.Transform;
import javax.xml.crypto.dsig.XMLSignature;
import javax.xml.crypto.dsig.XMLSignatureFactory;
import javax.xml.crypto.dsig.dom.DOMValidateContext;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Ticket 121b part 2: the NPSB switch mock signs what it sends with the dev switch key, in the one
 * XML-DSig profile mw-crypto verifies (enveloped, whole document, exclusive C14N, RSA-SHA256).
 */
class NpsbSignerTest {

  static final String PACS002 = NpsbIsoXml.pacs002("ST1", OffsetDateTime.of(2026, 11, 20, 9, 0, 0, 0, ZoneOffset.UTC),
      "M1", "E2E1", "T1", "ACSC", null, "NPSB-CLR-1");

  @Test
  void signsTheWholeDocumentInMwCryptosProfile() throws Exception {
    KeyPair keys = rsa();
    String signed = new NpsbSigner(pkcs8Pem(keys)).sign(PACS002);

    Document doc = parse(signed);
    NodeList sigs = doc.getElementsByTagNameNS(XMLSignature.XMLNS, "Signature");
    assertThat(sigs.getLength()).isEqualTo(1);
    Node sig = sigs.item(0);
    assertThat(sig.getParentNode()).isSameAs(doc.getDocumentElement());

    DOMValidateContext ctx = new DOMValidateContext(keys.getPublic(), sig);
    ctx.setProperty("org.jcp.xml.dsig.secureValidation", Boolean.TRUE);
    XMLSignature parsed = XMLSignatureFactory.getInstance("DOM").unmarshalXMLSignature(ctx);
    assertThat(parsed.validate(ctx)).isTrue();
    var si = parsed.getSignedInfo();
    assertThat(si.getCanonicalizationMethod().getAlgorithm()).isEqualTo(CanonicalizationMethod.EXCLUSIVE);
    assertThat(si.getSignatureMethod().getAlgorithm()).isEqualTo(SignatureMethod.RSA_SHA256);
    assertThat(si.getReferences()).hasSize(1);
    javax.xml.crypto.dsig.Reference ref = si.getReferences().getFirst();
    assertThat(ref.getURI()).isEmpty();
    assertThat(ref.getDigestMethod().getAlgorithm()).isEqualTo(DigestMethod.SHA256);
    assertThat(ref.getTransforms().stream().map(Transform::getAlgorithm).toList())
        .containsExactly(Transform.ENVELOPED, CanonicalizationMethod.EXCLUSIVE);
  }

  @Test
  void aTamperedSignedDocumentNoLongerVerifies() throws Exception {
    KeyPair keys = rsa();
    String tampered = new NpsbSigner(pkcs8Pem(keys)).sign(PACS002).replace("ACSC", "RJCT");

    Document doc = parse(tampered);
    Node sig = doc.getElementsByTagNameNS(XMLSignature.XMLNS, "Signature").item(0);
    DOMValidateContext ctx = new DOMValidateContext(keys.getPublic(), sig);
    assertThat(XMLSignatureFactory.getInstance("DOM").unmarshalXMLSignature(ctx).validate(ctx)).isFalse();
  }

  @Test
  void withoutAKeyTheDocumentGoesOutUnsigned() {
    NpsbSigner off = new NpsbSigner("");

    assertThat(off.enabled()).isFalse();
    assertThat(off.sign(PACS002)).isEqualTo(PACS002);
  }

  @Test
  void aKeyThatIsNotAPkcs8RsaKeyFailsAtStartUp() {
    assertThatThrownBy(() -> new NpsbSigner("-----BEGIN PRIVATE KEY-----\nbm90IGEga2V5\n-----END PRIVATE KEY-----"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("sim.npsb.signing-key");
  }

  static KeyPair rsa() throws Exception {
    KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
    g.initialize(2048);
    return g.generateKeyPair();
  }

  static String pkcs8Pem(KeyPair keys) {
    return "-----BEGIN PRIVATE KEY-----\n"
        + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(keys.getPrivate().getEncoded())
        + "\n-----END PRIVATE KEY-----\n";
  }

  private static Document parse(String xml) throws Exception {
    DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
    f.setNamespaceAware(true);
    return f.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
  }
}
