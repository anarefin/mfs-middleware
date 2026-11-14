package mfs.mw.crypto.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.PublicKey;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import javax.xml.crypto.dsig.CanonicalizationMethod;
import javax.xml.crypto.dsig.DigestMethod;
import javax.xml.crypto.dsig.Reference;
import javax.xml.crypto.dsig.SignatureMethod;
import javax.xml.crypto.dsig.SignedInfo;
import javax.xml.crypto.dsig.Transform;
import javax.xml.crypto.dsig.XMLObject;
import javax.xml.crypto.dsig.XMLSignature;
import javax.xml.crypto.dsig.XMLSignatureFactory;
import javax.xml.crypto.dsig.dom.DOMSignContext;
import javax.xml.crypto.dsig.dom.DOMValidateContext;
import javax.xml.crypto.dom.DOMStructure;
import javax.xml.crypto.dsig.spec.C14NMethodParameterSpec;
import javax.xml.crypto.dsig.spec.TransformParameterSpec;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import mfs.mw.crypto.dto.SignatureCheck;
import mfs.mw.crypto.dto.XmlDsigSignRequest;
import mfs.mw.crypto.dto.XmlDsigSignResponse;
import mfs.mw.crypto.dto.XmlDsigVerifyRequest;
import mfs.mw.crypto.entity.KeyState;
import mfs.mw.crypto.exception.MwException;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * XML-DSig sign and verify (ticket 65, spec 18.8) above the provider SPI: enveloped, RSA-SHA256,
 * exclusive C14N, the signature checked by the JDK's own XML-DSig validation; tampering, signature
 * wrapping, XXE and the size cap.
 */
class XmlDsigServiceTest {

  static final String PACS =
      "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
          + "<Document xmlns=\"urn:iso:std:iso:20022:tech:xsd:pacs.008.001.08\">\n"
          + "  <FIToFICstmrCdtTrf><GrpHdr><MsgId>NPSB-0001</MsgId></GrpHdr>\n"
          + "    <CdtTrfTxInf><IntrBkSttlmAmt Ccy=\"BDT\">1500.00</IntrBkSttlmAmt></CdtTrfTxInf>\n"
          + "  </FIToFICstmrCdtTrf>\n"
          + "</Document>\n";

  OperationFixture f;
  XmlDsigService xml;

  @BeforeEach
  void setUp() {
    f = new OperationFixture()
        .key("xmldsig", 1, "XMLDSIG", KeyState.VERIFY_ONLY)
        .key("xmldsig", 2, "XMLDSIG", KeyState.ACTIVE)
        .key("jwt-sign", 1, "JWT_SIGN", KeyState.ACTIVE);
    xml = new XmlDsigService(f.keys, f.callers, Clock.fixed(OperationFixture.T0, ZoneOffset.UTC));
  }

  static String b64(String s) {
    return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
  }

  static String text(String b64) {
    return new String(Base64.getDecoder().decode(b64), StandardCharsets.UTF_8);
  }

  static String code(Throwable e) {
    return ((MwException) e).code();
  }

  String signed() {
    return text(xml.sign(new XmlDsigSignRequest(null, b64(PACS))).xml());
  }

  SignatureCheck verify(String doc) {
    return xml.verify(new XmlDsigVerifyRequest(b64(doc), "xmldsig", null));
  }

  static Document parse(String doc) throws Exception {
    DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
    dbf.setNamespaceAware(true);
    return dbf.newDocumentBuilder().parse(new ByteArrayInputStream(doc.getBytes(StandardCharsets.UTF_8)));
  }

  static String serialize(Document doc) throws Exception {
    StringWriter out = new StringWriter();
    TransformerFactory.newInstance().newTransformer().transform(new DOMSource(doc), new StreamResult(out));
    return out.toString();
  }

  PublicKey publicKey(String kid) {
    return f.hsm.publicKey(kid);
  }

  @Test
  void signsEnvelopedRsaSha256ExclusiveC14nThatTheJdkValidates() throws Exception {
    XmlDsigSignResponse r = xml.sign(new XmlDsigSignRequest(null, b64(PACS)));
    assertThat(r.kid()).isEqualTo("xmldsig.v2");
    Document doc = parse(text(r.xml()));
    Element sig = (Element) doc.getElementsByTagNameNS(XMLSignature.XMLNS, "Signature").item(0);
    assertThat(sig.getParentNode()).isSameAs(doc.getDocumentElement());

    DOMValidateContext ctx = new DOMValidateContext(publicKey("xmldsig.v2"), sig);
    XMLSignature parsed = XMLSignatureFactory.getInstance("DOM").unmarshalXMLSignature(ctx);
    SignedInfo si = parsed.getSignedInfo();
    assertThat(si.getSignatureMethod().getAlgorithm()).isEqualTo(SignatureMethod.RSA_SHA256);
    assertThat(si.getCanonicalizationMethod().getAlgorithm()).isEqualTo(CanonicalizationMethod.EXCLUSIVE);
    Reference ref = si.getReferences().getFirst();
    assertThat(ref.getURI()).isEmpty();
    assertThat(ref.getDigestMethod().getAlgorithm()).isEqualTo(DigestMethod.SHA256);
    assertThat(ref.getTransforms()).extracting(t -> t.getAlgorithm())
        .containsExactly(Transform.ENVELOPED, CanonicalizationMethod.EXCLUSIVE);
    assertThat(parsed.validate(ctx)).isTrue();
    // the business content is unchanged
    assertThat(doc.getElementsByTagNameNS("*", "MsgId").item(0).getTextContent()).isEqualTo("NPSB-0001");
  }

  @Test
  void roundTripVerifiesUnderTheKeyIdAndThePreviousVersion() throws Exception {
    String doc = signed();
    assertThat(verify(doc)).isEqualTo(SignatureCheck.valid("xmldsig.v2"));
    assertThat(xml.verify(new XmlDsigVerifyRequest(b64(doc), "xmldsig.v2", null)).valid()).isTrue();
    assertThat(xml.verify(new XmlDsigVerifyRequest(b64(doc), "xmldsig.v1", null)))
        .isEqualTo(SignatureCheck.invalid("SIGNATURE_MISMATCH"));

    String old = text(xml.sign(new XmlDsigSignRequest("xmldsig", b64(PACS))).xml());
    assertThat(old).contains("xmldsig.v2");
    assertThatThrownBy(() -> xml.sign(new XmlDsigSignRequest("xmldsig.v1", b64(PACS))))
        .satisfies(e -> assertThat(code(e)).isEqualTo("MW-VAL-015"));
    // a document signed by the previous (VERIFY_ONLY) version still verifies
    String byV1 = jdkSign(PACS, f.hsm.key("xmldsig.v1"), "");
    assertThat(verify(byV1)).isEqualTo(SignatureCheck.valid("xmldsig.v1"));
  }

  @Test
  void aChangedElementOrAttributeFailsVerify() throws Exception {
    String doc = signed();
    assertThat(verify(doc.replace("1500.00", "9500.00"))).isEqualTo(SignatureCheck.invalid("DIGEST_MISMATCH"));
    assertThat(verify(doc.replace("Ccy=\"BDT\"", "Ccy=\"USD\""))).isEqualTo(SignatureCheck.invalid("DIGEST_MISMATCH"));
    assertThat(verify(doc.replace("<MsgId>", "<MsgId>X"))).isEqualTo(SignatureCheck.invalid("DIGEST_MISMATCH"));

    Document d = parse(doc);
    Element value = (Element) d.getElementsByTagNameNS(XMLSignature.XMLNS, "SignatureValue").item(0);
    byte[] sig = Base64.getMimeDecoder().decode(value.getTextContent());
    sig[10] ^= 1;
    value.setTextContent(Base64.getEncoder().encodeToString(sig));
    assertThat(verify(serialize(d))).isEqualTo(SignatureCheck.invalid("SIGNATURE_MISMATCH"));

    assertThat(verify(PACS)).isEqualTo(SignatureCheck.invalid("SIGNATURE_MISSING"));
  }

  @Test
  void signatureWrappingIsRefused() throws Exception {
    String doc = signed();
    // XSW 1: the signed document moved under a new root next to forged content
    Document original = parse(doc);
    Document wrapped = parse("<Evil><Document xmlns=\"urn:iso:std:iso:20022:tech:xsd:pacs.008.001.08\">"
        + "<IntrBkSttlmAmt Ccy=\"BDT\">999999.00</IntrBkSttlmAmt></Document></Evil>");
    wrapped.getDocumentElement().appendChild(wrapped.importNode(original.getDocumentElement(), true));
    assertThat(verify(serialize(wrapped))).isEqualTo(SignatureCheck.invalid("SIGNATURE_PLACEMENT"));

    // XSW 2: a correctly signed Id reference to the original, hidden in a ds:Object, beside forged content
    String xsw = jdkSignById(f.hsm.key("xmldsig.v2"));
    assertThat(xsw).contains("999999.00");
    assertThat(verify(xsw)).isEqualTo(SignatureCheck.invalid("REFERENCE_NOT_ALLOWED"));

    // two signatures: which one is meant is ambiguous
    Document twice = parse(doc);
    Element sig = (Element) twice.getElementsByTagNameNS(XMLSignature.XMLNS, "Signature").item(0);
    twice.getDocumentElement().appendChild(sig.cloneNode(true));
    assertThat(verify(serialize(twice))).isEqualTo(SignatureCheck.invalid("MULTIPLE_SIGNATURES"));

    // a signature over the whole document with an unsigned ds:Object carrying content
    Document object = parse(doc);
    Element s = (Element) object.getElementsByTagNameNS(XMLSignature.XMLNS, "Signature").item(0);
    Element obj = object.createElementNS(XMLSignature.XMLNS, "ds:Object");
    obj.setTextContent("unsigned");
    s.appendChild(obj);
    assertThat(verify(serialize(object))).isEqualTo(SignatureCheck.invalid("REFERENCE_NOT_ALLOWED"));
  }

  @Test
  void otherAlgorithmsAreRefused() throws Exception {
    String sha1 = jdkSign(PACS, f.hsm.key("xmldsig.v2"), "sha1");
    assertThat(verify(sha1)).isEqualTo(SignatureCheck.invalid("ALGORITHM_NOT_ALLOWED"));
  }

  @Test
  void xxeDoctypesAndOversizedDocumentsAreRefusedBeforeAnyHsmCall() {
    String xxe = "<?xml version=\"1.0\"?><!DOCTYPE d [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><d>&x;</d>";
    assertThatThrownBy(() -> xml.sign(new XmlDsigSignRequest(null, b64(xxe))))
        .satisfies(e -> assertThat(code(e)).isEqualTo("MW-VAL-001"));
    assertThatThrownBy(() -> verify(xxe)).satisfies(e -> assertThat(code(e)).isEqualTo("MW-VAL-001"));
    String laughs = "<?xml version=\"1.0\"?><!DOCTYPE l [<!ENTITY a \"aaaaaaaaaa\"><!ENTITY b \"&a;&a;&a;&a;\">]><l>&b;</l>";
    assertThatThrownBy(() -> verify(laughs)).satisfies(e -> assertThat(code(e)).isEqualTo("MW-VAL-001"));
    assertThatThrownBy(() -> verify("<not-closed>")).satisfies(e -> assertThat(code(e)).isEqualTo("MW-VAL-001"));
    String big = "<d>" + "x".repeat(XmlDsigService.MAX_XML_BYTES) + "</d>";
    assertThatThrownBy(() -> xml.sign(new XmlDsigSignRequest(null, b64(big))))
        .satisfies(e -> assertThat(code(e)).isEqualTo("MW-VAL-001"));
    assertThat(f.meters.find("crypto.op.duration").timers()).isEmpty();
  }

  @Test
  void onlyXmldsigKeysSign() {
    assertThatThrownBy(() -> xml.sign(new XmlDsigSignRequest("jwt-sign", b64(PACS))))
        .satisfies(e -> assertThat(code(e)).isEqualTo("MW-VAL-015"));
    assertThatThrownBy(() -> xml.verify(new XmlDsigVerifyRequest(b64(PACS), "jwt-sign", null)))
        .satisfies(e -> assertThat(code(e)).isEqualTo("MW-VAL-015"));
    assertThatThrownBy(() -> xml.verify(new XmlDsigVerifyRequest(b64(PACS), null, null)))
        .satisfies(e -> assertThat(code(e)).isEqualTo("MW-VAL-001"));
  }

  @Test
  void verifiesWithAPartnerCertificateOrPublicKeyInPem() throws Exception {
    KeyPair partner = (KeyPair) f.hsm.key("xmldsig.v1");
    String doc = jdkSign(PACS, partner, "");
    String cert = certificatePem(partner, OperationFixture.T0.minus(1, ChronoUnit.DAYS), OperationFixture.T0.plus(30, ChronoUnit.DAYS));
    assertThat(xml.verify(new XmlDsigVerifyRequest(b64(doc), null, cert))).isEqualTo(SignatureCheck.valid(null));
    assertThat(xml.verify(new XmlDsigVerifyRequest(b64(doc), null, pem(partner.getPublic())))).isEqualTo(SignatureCheck.valid(null));
    assertThat(xml.verify(new XmlDsigVerifyRequest(b64(doc.replace("1500", "1600")), null, cert)).valid()).isFalse();

    String expired = certificatePem(partner, OperationFixture.T0.minus(30, ChronoUnit.DAYS), OperationFixture.T0.minus(1, ChronoUnit.DAYS));
    assertThat(xml.verify(new XmlDsigVerifyRequest(b64(doc), null, expired)))
        .isEqualTo(SignatureCheck.invalid("CERTIFICATE_NOT_VALID"));
    assertThatThrownBy(() -> xml.verify(new XmlDsigVerifyRequest(b64(doc), null, "-----BEGIN CERTIFICATE-----\nAAAA\n-----END CERTIFICATE-----")))
        .satisfies(e -> assertThat(code(e)).isEqualTo("MW-VAL-001"));
    assertThatThrownBy(() -> xml.verify(new XmlDsigVerifyRequest(b64(doc), "xmldsig", cert)))
        .satisfies(e -> assertThat(code(e)).isEqualTo("MW-VAL-001"));
  }

  @Test
  void theKeyInfoOfTheDocumentIsNeverTrusted() throws Exception {
    // signed by a stranger who names our key in KeyName: verify uses only the key the caller gives
    f.key("stranger", 1, "XMLDSIG", KeyState.ACTIVE);
    String doc = jdkSign(PACS, f.hsm.key("stranger.v1"), "");
    assertThat(doc).contains("xmldsig.v2");
    assertThat(verify(doc)).isEqualTo(SignatureCheck.invalid("SIGNATURE_MISMATCH"));
  }

  // --- reference signatures made with the JDK directly (the partner side) ---

  /** {@code variant}: "" = the profile (KeyName xmldsig.v2), "sha1" = RSA-SHA1. */
  static String jdkSign(String content, Object key, String variant) throws Exception {
    XMLSignatureFactory fac = XMLSignatureFactory.getInstance("DOM");
    Document doc = parse(content);
    boolean sha1 = variant.equals("sha1");
    Reference ref = fac.newReference("", fac.newDigestMethod(sha1 ? DigestMethod.SHA1 : DigestMethod.SHA256, null),
        List.of(fac.newTransform(Transform.ENVELOPED, (TransformParameterSpec) null),
            fac.newTransform(CanonicalizationMethod.EXCLUSIVE, (TransformParameterSpec) null)), null, null);
    SignedInfo si = fac.newSignedInfo(
        fac.newCanonicalizationMethod(CanonicalizationMethod.EXCLUSIVE, (C14NMethodParameterSpec) null),
        fac.newSignatureMethod(sha1 ? SignatureMethod.RSA_SHA1 : SignatureMethod.RSA_SHA256, null), List.of(ref));
    var kif = fac.getKeyInfoFactory();
    DOMSignContext ctx = new DOMSignContext(((KeyPair) key).getPrivate(), doc.getDocumentElement());
    ctx.setDefaultNamespacePrefix("ds");
    fac.newXMLSignature(si, kif.newKeyInfo(List.of(kif.newKeyName("xmldsig.v2")))).sign(ctx);
    return serialize(doc);
  }

  /** The classic wrapping attack: forged root content, the signed original (Id p1) inside ds:Object. */
  static String jdkSignById(Object key) throws Exception {
    XMLSignatureFactory fac = XMLSignatureFactory.getInstance("DOM");
    Document doc = parse("<Document><Pmt>999999.00</Pmt></Document>");
    Element original = doc.createElement("Pmt");
    original.setAttribute("Id", "p1");
    original.setIdAttribute("Id", true);
    original.setTextContent("15.00");
    XMLObject object = fac.newXMLObject(List.of(new DOMStructure(original)), null, null, null);
    Reference ref = fac.newReference("#p1", fac.newDigestMethod(DigestMethod.SHA256, null),
        List.of(fac.newTransform(CanonicalizationMethod.EXCLUSIVE, (TransformParameterSpec) null)), null, null);
    SignedInfo si = fac.newSignedInfo(
        fac.newCanonicalizationMethod(CanonicalizationMethod.EXCLUSIVE, (C14NMethodParameterSpec) null),
        fac.newSignatureMethod(SignatureMethod.RSA_SHA256, null), List.of(ref));
    DOMSignContext ctx = new DOMSignContext(((KeyPair) key).getPrivate(), doc.getDocumentElement());
    ctx.setDefaultNamespacePrefix("ds");
    XMLSignature sig = fac.newXMLSignature(si, null, List.of(object), null, null);
    sig.sign(ctx);
    // the JDK itself accepts it: the attack works against a verifier without a profile
    Element s = (Element) doc.getElementsByTagNameNS(XMLSignature.XMLNS, "Signature").item(0);
    DOMValidateContext v = new DOMValidateContext(((KeyPair) key).getPublic(), s);
    v.setIdAttributeNS(original, null, "Id");
    assertThat(fac.unmarshalXMLSignature(v).validate(v)).isTrue();
    return serialize(doc);
  }

  static String certificatePem(KeyPair pair, Instant from, Instant to) throws Exception {
    X500Name name = new X500Name("CN=npsb-partner");
    var cert = new JcaX509CertificateConverter().getCertificate(new JcaX509v3CertificateBuilder(
        name, BigInteger.ONE, Date.from(from), Date.from(to), name, pair.getPublic())
        .build(new JcaContentSignerBuilder("SHA256withRSA").build(pair.getPrivate())));
    return pem(cert);
  }

  static String pem(Object o) throws Exception {
    StringWriter out = new StringWriter();
    try (JcaPEMWriter w = new JcaPEMWriter(out)) {
      w.writeObject(o);
    }
    return out.toString();
  }
}
