package mfs.mw.crypto.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PSSParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import javax.xml.crypto.dsig.XMLSignature;
import javax.xml.crypto.dsig.XMLSignatureFactory;
import javax.xml.crypto.dsig.dom.DOMValidateContext;
import javax.xml.parsers.DocumentBuilderFactory;
import mfs.mw.crypto.HsmStack;
import mfs.mw.crypto.security.InternalTokens;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * XML-DSig and RFC 9421 HTTP signatures (ticket 65) against the SoftHSM token, over the API of
 * mw-crypto's own image ({@link HsmStack}): every signature is made in the HSM and checked by the JDK
 * with the public key the API publishes, then by mw-crypto's verify; tampering fails. Keys of its own
 * keyIds; the IT client mw-auth is bound to the purposes by HsmStack's test-only authz override.
 * Finally the document content and component values never reached a log line.
 */
class SignaturesIT {

  static final JsonMapper JSON = JsonMapper.builder().build();
  static final String AMOUNT = "48213.77";
  static final String DOC = "<Document xmlns=\"urn:iso:std:iso:20022:tech:xsd:pacs.008.001.08\"><GrpHdr><MsgId>"
      + "NPSB-IT-1</MsgId></GrpHdr><Amt Ccy=\"BDT\">" + AMOUNT + "</Amt></Document>";
  static final String DIGEST = "sha-256=:c2VjcmV0LWRpZ2VzdC12YWx1ZS1pdA==:";
  static final String COMPONENTS = "[{\"name\":\"@method\",\"value\":\"POST\"},{\"name\":\"@authority\","
      + "\"value\":\"npsb.example\"},{\"name\":\"content-digest\",\"value\":\"" + DIGEST + "\"}]";

  @BeforeAll
  static void keys() {
    HsmStack.start();
    for (String[] k : new String[][] {{"XMLDSIG", "xmldsig-it"}, {"HTTP_SIGN_RSA", "http-sign-rsa-it"},
        {"HTTP_SIGN_EC", "http-sign-ec-it"}, {"HMAC_IT", "hmac-sig-it"}}) {
      HttpResponse<String> r = HsmStack.post("/internal/v1/keys", "{\"purpose\":\"" + k[0] + "\",\"keyId\":\"" + k[1]
          + "\"}", InternalTokens.forClient("mw-admin-api"));
      assertThat(r.statusCode()).as(r.body()).isIn(201, 409);
    }
  }

  @AfterAll
  static void nothingOfTheMessagesReachedTheLog() {
    String logs = HsmStack.start().getLogs();
    assertThat(logs).doesNotContain(AMOUNT).doesNotContain("NPSB-IT-1").doesNotContain(DIGEST)
        .doesNotContain(b64(DOC)).doesNotContain("npsb.example");
  }

  static String token() {
    return InternalTokens.forClient("mw-auth");
  }

  static JsonNode ok(HttpResponse<String> r) {
    assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
    return JSON.readTree(r.body());
  }

  static String b64(String s) {
    return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
  }

  static PublicKey publicKey(String keyId, String alg) throws Exception {
    JsonNode pub = ok(HsmStack.get("/internal/v1/keys/" + keyId + "/public", token()));
    String pem = pub.get("versions").get(0).get("pem").asString()
        .replace("-----BEGIN PUBLIC KEY-----", "").replace("-----END PUBLIC KEY-----", "").replaceAll("\\s", "");
    return KeyFactory.getInstance(alg).generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(pem)));
  }

  static String verifyXml(String xml) {
    return ok(HsmStack.post("/internal/v1/xmldsig/verify", "{\"keyId\":\"xmldsig-it\",\"xml\":\"" + b64(xml) + "\"}",
        token())).toString();
  }

  @Test
  void xmlDsigIsSignedInTheHsmAndVerifies() throws Exception {
    JsonNode signed = ok(HsmStack.post("/internal/v1/xmldsig/sign", "{\"keyId\":\"xmldsig-it\",\"xml\":\"" + b64(DOC)
        + "\"}", token()));
    assertThat(signed.get("kid").asString()).isEqualTo("xmldsig-it.v1");
    String xml = new String(Base64.getDecoder().decode(signed.get("xml").asString()), StandardCharsets.UTF_8);

    DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
    dbf.setNamespaceAware(true);
    Document doc = dbf.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    Element sig = (Element) doc.getElementsByTagNameNS(XMLSignature.XMLNS, "Signature").item(0);
    DOMValidateContext ctx = new DOMValidateContext(publicKey("xmldsig-it", "RSA"), sig);
    assertThat(XMLSignatureFactory.getInstance("DOM").unmarshalXMLSignature(ctx).validate(ctx)).isTrue();

    assertThat(verifyXml(xml)).isEqualTo("{\"valid\":true,\"kid\":\"xmldsig-it.v1\"}");
    assertThat(verifyXml(xml.replace(AMOUNT, "1.00"))).isEqualTo("{\"valid\":false,\"reason\":\"DIGEST_MISMATCH\"}");
    HttpResponse<String> xxe = HsmStack.post("/internal/v1/xmldsig/sign", "{\"keyId\":\"xmldsig-it\",\"xml\":\""
        + b64("<!DOCTYPE d [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><d>&x;</d>") + "\"}", token());
    assertThat(xxe.statusCode()).isEqualTo(400);
  }

  JsonNode httpSign(String keyId, String alg) {
    return ok(HsmStack.post("/internal/v1/http-signature/sign", "{\"keyId\":\"" + keyId + "\""
        + (alg == null ? "" : ",\"alg\":\"" + alg + "\"") + ",\"components\":" + COMPONENTS + "}", token()));
  }

  String httpVerify(String keyId, JsonNode signed, String components) {
    return ok(HsmStack.post("/internal/v1/http-signature/verify", "{\"keyId\":\"" + keyId + "\",\"signatureInput\":"
        + JSON.writeValueAsString(signed.get("signatureInput").asString()) + ",\"signature\":\""
        + signed.get("signature").asString() + "\",\"components\":" + components
        + ",\"requiredComponents\":[\"@method\",\"content-digest\"],\"maxAgeSeconds\":300}", token())).toString();
  }

  static byte[] base(JsonNode signed) {
    String input = signed.get("signatureInput").asString();
    return ("\"@method\": POST\n\"@authority\": npsb.example\n\"content-digest\": " + DIGEST + "\n\"@signature-params\": "
        + input.substring(input.indexOf('=') + 1)).getBytes(StandardCharsets.US_ASCII);
  }

  static byte[] signature(JsonNode signed) {
    String s = signed.get("signature").asString();
    return Base64.getDecoder().decode(s.substring(s.indexOf(':') + 1, s.length() - 1));
  }

  @Test
  void httpSignaturesAreSignedInTheHsmAndVerify() throws Exception {
    JsonNode ec = httpSign("http-sign-ec-it", null);
    Signature es = Signature.getInstance("SHA256withECDSAinP1363Format");
    es.initVerify(publicKey("http-sign-ec-it", "EC"));
    es.update(base(ec));
    assertThat(es.verify(signature(ec))).isTrue();

    JsonNode pss = httpSign("http-sign-rsa-it", null);
    assertThat(pss.get("alg").asString()).isEqualTo("rsa-pss-sha512");
    Signature ps = Signature.getInstance("RSASSA-PSS");
    ps.setParameter(new PSSParameterSpec("SHA-512", "MGF1", MGF1ParameterSpec.SHA512, 64, 1));
    ps.initVerify(publicKey("http-sign-rsa-it", "RSA"));
    ps.update(base(pss));
    assertThat(ps.verify(signature(pss))).isTrue();

    JsonNode v15 = httpSign("http-sign-rsa-it", "rsa-v1_5-sha256");
    JsonNode hmac = httpSign("hmac-sig-it", null);
    String tampered = COMPONENTS.replace("npsb.example", "evil.example");
    for (String[] k : new String[][] {{"http-sign-ec-it", "ec"}, {"http-sign-rsa-it", "pss"}, {"http-sign-rsa-it", "v15"},
        {"hmac-sig-it", "hmac"}}) {
      JsonNode signed = switch (k[1]) {
        case "ec" -> ec;
        case "pss" -> pss;
        case "v15" -> v15;
        default -> hmac;
      };
      assertThat(httpVerify(k[0], signed, COMPONENTS)).as(k[1]).isEqualTo("{\"valid\":true,\"kid\":\"" + k[0] + ".v1\"}");
      assertThat(httpVerify(k[0], signed, tampered)).as(k[1]).isEqualTo("{\"valid\":false,\"reason\":\"SIGNATURE_MISMATCH\"}");
    }
  }
}
