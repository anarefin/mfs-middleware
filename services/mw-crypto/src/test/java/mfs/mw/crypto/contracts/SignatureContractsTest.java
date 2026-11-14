package mfs.mw.crypto.contracts;

import static mfs.mw.crypto.contracts.OperationContractsTest.invalidRequest;
import static mfs.mw.crypto.contracts.OperationContractsTest.request;
import static mfs.mw.crypto.contracts.OperationContractsTest.validRequest;
import static mfs.mw.crypto.contracts.OperationContractsTest.validResponse;
import static org.assertj.core.api.Assertions.assertThat;

import mfs.mw.crypto.dto.HttpSignatureSignRequest;
import mfs.mw.crypto.dto.HttpSignatureSignResponse;
import mfs.mw.crypto.dto.HttpSignatureVerifyRequest;
import mfs.mw.crypto.dto.SignatureCheck;
import mfs.mw.crypto.dto.XmlDsigSignRequest;
import mfs.mw.crypto.dto.XmlDsigSignResponse;
import mfs.mw.crypto.dto.XmlDsigVerifyRequest;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * The ticket 65 endpoints of {@code api/mw-crypto/crypto-internal.v1}: the request fixtures an adapter
 * sends validate against the request schema, read into the DTO and pass its Bean Validation; every
 * response DTO, serialised as Spring MVC does, validates against the response schema; invalid
 * requests fail both.
 */
class SignatureContractsTest {

  static final JsonMapper JSON = ProducedContractsTest.JSON;
  static final String XML = "PERvY3VtZW50Lz4=";
  static final String PEM = "-----BEGIN PUBLIC KEY-----\\nMFkw\\n-----END PUBLIC KEY-----\\n";
  static final String COMPONENTS = "[{\"name\":\"@method\",\"value\":\"POST\"},"
      + "{\"name\":\"@query-param\",\"params\":{\"name\":\"Pet\"},\"value\":\"dog\"},"
      + "{\"name\":\"example-dict\",\"params\":{\"key\":\"a\",\"sf\":true},\"value\":\"1\"},"
      + "{\"name\":\"content-digest\",\"value\":\"sha-256=:X48E9qOokqqrvdts8nOJRJN3OWDUoyWxBf7kbu9DBPE=:\"}]";

  @Test
  void xmlDsig() {
    String sign = "/internal/v1/xmldsig/sign";
    validRequest(sign, XmlDsigSignRequest.class, "{\"xml\":\"" + XML + "\"}");
    validRequest(sign, XmlDsigSignRequest.class, "{\"keyId\":\"xmldsig-npsb\",\"xml\":\"" + XML + "\"}");
    invalidRequest(sign, XmlDsigSignRequest.class, "{\"xml\":\"<Document/>\"}");
    invalidRequest(sign, XmlDsigSignRequest.class, "{\"xml\":\"\"}");
    validResponse(sign, new XmlDsigSignResponse(XML, "xmldsig.v1"));

    String verify = "/internal/v1/xmldsig/verify";
    validRequest(verify, XmlDsigVerifyRequest.class, "{\"xml\":\"" + XML + "\",\"keyId\":\"xmldsig.v1\"}");
    validRequest(verify, XmlDsigVerifyRequest.class, "{\"xml\":\"" + XML + "\",\"certificatePem\":\"" + PEM + "\"}");
    assertThat(request(verify).validate(JSON.readTree("{\"xml\":\"" + XML + "\"}"))).isNotEmpty();
    assertThat(request(verify).validate(JSON.readTree("{\"xml\":\"" + XML + "\",\"keyId\":\"xmldsig\",\"certificatePem\":\""
        + PEM + "\"}"))).isNotEmpty();
    validResponse(verify, SignatureCheck.valid("xmldsig.v1"));
    validResponse(verify, SignatureCheck.valid(null));
    validResponse(verify, SignatureCheck.invalid("SIGNATURE_PLACEMENT"));
  }

  @Test
  void httpSignatures() {
    String sign = "/internal/v1/http-signature/sign";
    validRequest(sign, HttpSignatureSignRequest.class, "{\"keyId\":\"http-sign-ec\",\"components\":" + COMPONENTS + "}");
    validRequest(sign, HttpSignatureSignRequest.class, "{\"keyId\":\"http-sign-rsa\",\"alg\":\"rsa-v1_5-sha256\","
        + "\"label\":\"npsb\",\"components\":" + COMPONENTS + ",\"created\":1763107200,\"expires\":1763107500,"
        + "\"nonce\":\"n-1\",\"tag\":\"npsb\"}");
    invalidRequest(sign, HttpSignatureSignRequest.class, "{\"keyId\":\"http-sign-ec\",\"components\":[]}");
    invalidRequest(sign, HttpSignatureSignRequest.class,
        "{\"keyId\":\"http-sign-ec\",\"alg\":\"rsa-sha1\",\"components\":" + COMPONENTS + "}");
    invalidRequest(sign, HttpSignatureSignRequest.class,
        "{\"keyId\":\"http-sign-ec\",\"components\":[{\"name\":\"Content-Type\",\"value\":\"a\"}]}");
    validResponse(sign, new HttpSignatureSignResponse("sig1", "http-sign-ec.v1", "ecdsa-p256-sha256",
        "sig1=(\"@method\");created=1763107200;keyid=\"http-sign-ec.v1\";alg=\"ecdsa-p256-sha256\"", "sig1=:AAAA:"));

    String verify = "/internal/v1/http-signature/verify";
    String input = "\"signatureInput\":\"sig1=(\\\"@method\\\");created=1763107200\",\"signature\":\"sig1=:AAAA:\"";
    validRequest(verify, HttpSignatureVerifyRequest.class, "{\"keyId\":\"hmac-partner\"," + input + ",\"components\":"
        + COMPONENTS + ",\"requiredComponents\":[\"@method\",\"content-digest\"],\"expectedKeyid\":\"partner-key-1\","
        + "\"maxAgeSeconds\":300,\"alg\":\"hmac-sha256\",\"label\":\"sig1\"}");
    validRequest(verify, HttpSignatureVerifyRequest.class, "{\"certificatePem\":\"" + PEM + "\"," + input
        + ",\"components\":" + COMPONENTS + "}");
    invalidRequest(verify, HttpSignatureVerifyRequest.class, "{\"keyId\":\"hmac-partner\",\"signature\":\"sig1=:AAAA:\","
        + "\"components\":[]}");
    // required components by full identity: a bare name or the serialised identifier with parameters
    validRequest(verify, HttpSignatureVerifyRequest.class, "{\"keyId\":\"hmac-partner\"," + input + ",\"components\":"
        + COMPONENTS + ",\"requiredComponents\":[\"@method\",\"\\\"example-dict\\\";key=\\\"a\\\";sf\","
        + "\"\\\"content-digest\\\"\"]}");
    invalidRequest(verify, HttpSignatureVerifyRequest.class, "{\"keyId\":\"hmac-partner\"," + input + ",\"components\":"
        + COMPONENTS + ",\"requiredComponents\":[\"\\\"content-digest\"]}");
    invalidRequest(verify, HttpSignatureVerifyRequest.class, "{\"keyId\":\"hmac-partner\"," + input + ",\"components\":"
        + COMPONENTS + ",\"requiredComponents\":[\"content-digest;sf\"]}");
    validResponse(verify, SignatureCheck.valid("http-sign-ec.v1"));
    validResponse(verify, SignatureCheck.invalid("TOO_OLD"));
  }
}
