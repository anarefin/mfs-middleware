package mfs.mw.crypto.service;

import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.time.Clock;
import java.util.List;
import java.util.function.Predicate;
import javax.xml.crypto.MarshalException;
import javax.xml.crypto.dom.DOMStructure;
import javax.xml.crypto.dsig.CanonicalizationMethod;
import javax.xml.crypto.dsig.DigestMethod;
import javax.xml.crypto.dsig.Reference;
import javax.xml.crypto.dsig.SignatureMethod;
import javax.xml.crypto.dsig.SignedInfo;
import javax.xml.crypto.dsig.Transform;
import javax.xml.crypto.dsig.XMLSignature;
import javax.xml.crypto.dsig.XMLSignatureException;
import javax.xml.crypto.dsig.XMLSignatureFactory;
import javax.xml.crypto.dsig.dom.DOMSignContext;
import javax.xml.crypto.dsig.dom.DOMValidateContext;
import javax.xml.crypto.dsig.keyinfo.KeyInfoFactory;
import javax.xml.crypto.dsig.spec.C14NMethodParameterSpec;
import javax.xml.crypto.dsig.spec.TransformParameterSpec;
import mfs.mw.crypto.dto.SignatureCheck;
import mfs.mw.crypto.dto.XmlDsigSignRequest;
import mfs.mw.crypto.dto.XmlDsigSignResponse;
import mfs.mw.crypto.dto.XmlDsigVerifyRequest;
import mfs.mw.crypto.exception.CryptoErrors;
import mfs.mw.crypto.exception.ErrorCodes;
import mfs.mw.crypto.exception.MwException;
import mfs.mw.crypto.security.CallerPolicy;
import mfs.mw.crypto.service.HsmKeys.Resolved;
import mfs.mw.crypto.service.HsmKeys.Use;
import mfs.mw.crypto.util.Bytes;
import mfs.mw.crypto.util.PemKeys;
import mfs.mw.crypto.util.SafeXml;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * XML-DSig sign and verify for NPSB / BanglaQR (ISO 20022) and the SOAP partners (ticket 65, spec
 * 18.8), one profile only: an enveloped signature over the whole document ({@code Reference URI=""},
 * transforms enveloped-signature + exclusive C14N, SHA-256), {@code SignedInfo} in exclusive C14N,
 * RSA-SHA256, {@code KeyInfo/KeyName} = the key version. The signature is made in the HSM with the
 * {@code XMLDSIG} key ({@link HsmXmlSignatureProvider}).
 *
 * <p>Verify accepts only that profile, which is what stops signature wrapping: exactly one {@code
 * ds:Signature}, a child of the document element, one reference to the whole document, no {@code
 * ds:Object}; the key is the one the caller names ({@code keyId} or a PEM), never the document's
 * {@code KeyInfo}; the JDK's secure validation is on. XXE is off and the document is at most 1 MiB
 * ({@link SafeXml}).
 */
@Service
public class XmlDsigService {

  static final int MAX_XML_BYTES = 1_048_576;
  static final String PURPOSE = "XMLDSIG";
  private static final Predicate<KeyPurpose> XMLDSIG = p -> p.name().equals(PURPOSE);
  private static final String SECURE = "org.jcp.xml.dsig.secureValidation";
  private static final String SIGNATURE_PROVIDER = "org.jcp.xml.dsig.internal.dom.SignatureProvider";
  private static final List<String> TRANSFORMS = List.of(Transform.ENVELOPED, CanonicalizationMethod.EXCLUSIVE);

  private final HsmKeys keys;
  private final CallerPolicy callers;
  private final Clock clock;
  private final XMLSignatureFactory factory = XMLSignatureFactory.getInstance("DOM");
  private final HsmXmlSignatureProvider hsmSignature = new HsmXmlSignatureProvider();

  public XmlDsigService(HsmKeys keys, CallerPolicy callers, Clock clock) {
    this.keys = keys;
    this.callers = callers;
    this.clock = clock;
  }

  public XmlDsigSignResponse sign(XmlDsigSignRequest r) {
    callers.requirePurpose(PURPOSE, "xmldsig-sign");
    Resolved key =
        r.keyId() != null
            ? keys.resolve(r.keyId(), Use.CREATE, XMLDSIG, "xmldsig-sign")
            : keys.activeByPurpose(PURPOSE, XMLDSIG, "xmldsig-sign");
    Document doc = parse(r.xml());
    try {
      Reference ref = factory.newReference("", factory.newDigestMethod(DigestMethod.SHA256, null),
          List.of(factory.newTransform(Transform.ENVELOPED, (TransformParameterSpec) null),
              factory.newTransform(CanonicalizationMethod.EXCLUSIVE, (TransformParameterSpec) null)),
          null, null);
      SignedInfo si = factory.newSignedInfo(
          factory.newCanonicalizationMethod(CanonicalizationMethod.EXCLUSIVE, (C14NMethodParameterSpec) null),
          factory.newSignatureMethod(SignatureMethod.RSA_SHA256, null), List.of(ref));
      KeyInfoFactory kif = factory.getKeyInfoFactory();
      DOMSignContext ctx = new DOMSignContext(new HsmXmlSignatureProvider.HsmKey(keys, key), doc.getDocumentElement());
      ctx.setDefaultNamespacePrefix("ds");
      ctx.setProperty(SECURE, Boolean.TRUE);
      ctx.setProperty(SIGNATURE_PROVIDER, hsmSignature);
      factory.newXMLSignature(si, kif.newKeyInfo(List.of(kif.newKeyName(key.kid())))).sign(ctx);
    } catch (MarshalException | XMLSignatureException | GeneralSecurityException e) {
      throw hsmFailure(e);
    }
    return new XmlDsigSignResponse(Bytes.encode(SafeXml.serialize(doc)), key.kid());
  }

  public SignatureCheck verify(XmlDsigVerifyRequest r) {
    if ((r.keyId() == null) == (r.certificatePem() == null)) {
      throw CryptoErrors.invalidField("keyId", "exactly one of keyId and certificatePem");
    }
    callers.requirePurpose(PURPOSE, "xmldsig-verify");
    List<Resolved> candidates = null;
    PemKeys partner = null;
    if (r.keyId() != null) {
      candidates = VerifyKeys.candidates(keys, r.keyId(), XMLDSIG, "xmldsig-verify");
    } else {
      partner = PemKeys.parse("certificatePem", r.certificatePem(), clock.instant());
    }
    Document doc = parse(r.xml());
    NodeList found = doc.getElementsByTagNameNS(XMLSignature.XMLNS, "Signature");
    if (found.getLength() == 0) {
      return SignatureCheck.invalid("SIGNATURE_MISSING");
    }
    if (found.getLength() > 1) {
      return SignatureCheck.invalid("MULTIPLE_SIGNATURES");
    }
    Element sig = (Element) found.item(0);
    if (sig.getParentNode() != doc.getDocumentElement()) {
      return SignatureCheck.invalid("SIGNATURE_PLACEMENT");
    }
    String profile = profile(sig);
    if (profile != null) {
      return SignatureCheck.invalid(profile);
    }
    if (partner != null) {
      if (!partner.validNow()) {
        return SignatureCheck.invalid("CERTIFICATE_NOT_VALID");
      }
      String failed = check(sig, partner.key());
      return failed == null ? SignatureCheck.valid(null) : SignatureCheck.invalid(failed);
    }
    String failed = "SIGNATURE_MISMATCH";
    for (Resolved key : candidates) {
      PublicKey pub = keys.run("public-key", key, p -> p.publicKey(key.label()));
      failed = check(sig, pub);
      if (failed == null) {
        return SignatureCheck.valid(key.kid());
      }
      if (failed.equals("DIGEST_MISMATCH")) {
        break;
      }
    }
    return SignatureCheck.invalid(failed);
  }

  /** Null when {@code sig} is the one profile, else the reason it is not. */
  private String profile(Element sig) {
    XMLSignature parsed;
    try {
      parsed = factory.unmarshalXMLSignature(new DOMStructure(sig));
    } catch (MarshalException | RuntimeException e) {
      return "MALFORMED_SIGNATURE";
    }
    SignedInfo si = parsed.getSignedInfo();
    if (!CanonicalizationMethod.EXCLUSIVE.equals(si.getCanonicalizationMethod().getAlgorithm())
        || !SignatureMethod.RSA_SHA256.equals(si.getSignatureMethod().getAlgorithm())) {
      return "ALGORITHM_NOT_ALLOWED";
    }
    if (si.getReferences().size() != 1 || !parsed.getObjects().isEmpty()) {
      return "REFERENCE_NOT_ALLOWED";
    }
    Reference ref = si.getReferences().getFirst();
    if (!"".equals(ref.getURI()) || ref.getType() != null) {
      return "REFERENCE_NOT_ALLOWED";
    }
    List<String> transforms = ref.getTransforms().stream().map(t -> t.getAlgorithm()).toList();
    if (!DigestMethod.SHA256.equals(ref.getDigestMethod().getAlgorithm()) || !transforms.equals(TRANSFORMS)) {
      return "ALGORITHM_NOT_ALLOWED";
    }
    return null;
  }

  /** Null when the signature verifies under {@code key}, else {@code DIGEST_MISMATCH} or {@code SIGNATURE_MISMATCH}. */
  private String check(Element sig, PublicKey key) {
    DOMValidateContext ctx = new DOMValidateContext(key, sig);
    ctx.setProperty(SECURE, Boolean.TRUE);
    try {
      XMLSignature parsed = factory.unmarshalXMLSignature(ctx);
      if (parsed.validate(ctx)) {
        return null;
      }
      Reference ref = parsed.getSignedInfo().getReferences().getFirst();
      return ref.validate(ctx) ? "SIGNATURE_MISMATCH" : "DIGEST_MISMATCH";
    } catch (MarshalException | XMLSignatureException e) {
      return "MALFORMED_SIGNATURE";
    }
  }

  private static Document parse(String base64) {
    byte[] xml = Bytes.decode("xml", base64);
    if (xml.length > MAX_XML_BYTES) {
      throw CryptoErrors.invalidField("xml", "larger than " + MAX_XML_BYTES + " bytes");
    }
    return SafeXml.parse("xml", xml);
  }

  /** An HSM failure inside the JDK signer comes back wrapped: keep it a 503, everything else a 500. */
  private static MwException hsmFailure(Exception e) {
    for (Throwable t = e; t != null; t = t.getCause()) {
      if (t instanceof MwException m) {
        return m;
      }
    }
    return new MwException(ErrorCodes.SYS_INTERNAL, "XML-DSig signing failed", e);
  }
}
