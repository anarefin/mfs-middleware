package mfs.mw.crypto.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import mfs.mw.crypto.config.SecurityConfig;
import mfs.mw.crypto.dto.HttpSignatureSignResponse;
import mfs.mw.crypto.dto.SignatureCheck;
import mfs.mw.crypto.dto.XmlDsigSignResponse;
import mfs.mw.crypto.exception.CryptoErrors;
import mfs.mw.crypto.security.InternalTokens;
import mfs.mw.crypto.service.HttpSignatureService;
import mfs.mw.crypto.service.XmlDsigService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * The ticket 65 endpoints of crypto-internal.v1: mapping, Bean Validation before the service (a bad
 * body is MW-VAL-001 and its values never come back), the verify answer and an HSM outage.
 */
@WebMvcTest(
    controllers = {XmlDsigController.class, HttpSignatureController.class},
    properties = "spring.test.mockmvc.print=none")
@Import(SecurityConfig.class)
class SignatureControllersTest {

  @DynamicPropertySource
  static void jwks(DynamicPropertyRegistry r) {
    r.add("mw.internal-auth.jwk-set-uri", InternalTokens::jwkSetUri);
  }

  final MockMvcTester mvc;
  @MockitoBean XmlDsigService xml;
  @MockitoBean HttpSignatureService http;

  @Autowired
  SignatureControllersTest(MockMvcTester mvc) {
    this.mvc = mvc;
  }

  MvcTestResult post(String path, String json) {
    return mvc.post().uri(path).header(HttpHeaders.AUTHORIZATION, InternalTokens.bearer("mw-auth"))
        .contentType(MediaType.APPLICATION_JSON).content(json).exchange();
  }

  static void invalid(MvcTestResult r, String... notEchoed) throws Exception {
    assertThat(r).hasStatus(400);
    assertThat(r).bodyJson().extractingPath("$.code").isEqualTo("MW-VAL-001");
    for (String s : notEchoed) {
      assertThat(r.getResponse().getContentAsString()).doesNotContain(s);
    }
  }

  @Test
  void xmlDsigSignAndVerify() throws Exception {
    when(xml.sign(any())).thenReturn(new XmlDsigSignResponse("PGQvPg==", "xmldsig.v1"));
    assertThat(post("/internal/v1/xmldsig/sign", "{\"xml\":\"PGQvPg==\"}")).hasStatusOk()
        .bodyJson().extractingPath("$.kid").isEqualTo("xmldsig.v1");
    when(xml.verify(any())).thenReturn(SignatureCheck.invalid("DIGEST_MISMATCH"));
    MvcTestResult v = post("/internal/v1/xmldsig/verify", "{\"xml\":\"PGQvPg==\",\"keyId\":\"xmldsig\"}");
    assertThat(v).hasStatusOk();
    assertThat(v.getResponse().getContentAsString()).isEqualTo("{\"valid\":false,\"reason\":\"DIGEST_MISMATCH\"}");

    when(xml.sign(any())).thenThrow(CryptoErrors.hsmUnavailable("xmldsig-sign", null));
    assertThat(post("/internal/v1/xmldsig/sign", "{\"xml\":\"PGQvPg==\"}")).hasStatus(503);
  }

  @Test
  void aBadXmlDsigBodyIsRefusedBeforeTheService() throws Exception {
    invalid(post("/internal/v1/xmldsig/sign", "{\"xml\":\"<d>not base64</d>\"}"), "not base64");
    invalid(post("/internal/v1/xmldsig/sign", "{\"xml\":\"\"}"));
    invalid(post("/internal/v1/xmldsig/verify", "{\"xml\":\"PGQvPg==\",\"keyId\":\"Bad Key\"}"), "Bad Key");
    invalid(post("/internal/v1/xmldsig/sign", "{\"xml\":\"" + "A".repeat(1_398_108) + "\"}"));
    verifyNoInteractions(xml);
  }

  @Test
  void httpSignatureSignAndVerify() throws Exception {
    when(http.sign(any())).thenReturn(new HttpSignatureSignResponse("sig1", "http-sign-ec.v1", "ecdsa-p256-sha256",
        "sig1=(\"@method\");created=1;keyid=\"http-sign-ec.v1\";alg=\"ecdsa-p256-sha256\"", "sig1=:AAAA:"));
    MvcTestResult s = post("/internal/v1/http-signature/sign",
        "{\"keyId\":\"http-sign-ec\",\"components\":[{\"name\":\"@method\",\"value\":\"POST\"}]}");
    assertThat(s).hasStatusOk();
    assertThat(s).bodyJson().extractingPath("$.signature").isEqualTo("sig1=:AAAA:");
    when(http.verify(any())).thenReturn(SignatureCheck.valid("http-sign-ec.v1"));
    assertThat(post("/internal/v1/http-signature/verify", "{\"keyId\":\"http-sign-ec\",\"signatureInput\":\"sig1=()\","
        + "\"signature\":\"sig1=:AAAA:\",\"components\":[]}")).hasStatusOk()
        .bodyJson().extractingPath("$.valid").isEqualTo(true);
  }

  @Test
  void aBadHttpSignatureBodyIsRefusedBeforeTheService() throws Exception {
    invalid(post("/internal/v1/http-signature/sign", "{\"keyId\":\"http-sign-ec\",\"components\":[]}"));
    invalid(post("/internal/v1/http-signature/sign",
        "{\"keyId\":\"http-sign-ec\",\"components\":[{\"name\":\"Content-Type\",\"value\":\"secret-v\"}]}"), "secret-v");
    invalid(post("/internal/v1/http-signature/sign",
        "{\"keyId\":\"http-sign-ec\",\"alg\":\"rsa-sha1\",\"components\":[{\"name\":\"@method\",\"value\":\"POST\"}]}"));
    invalid(post("/internal/v1/http-signature/sign",
        "{\"keyId\":\"http-sign-ec\",\"label\":\"Sig\",\"components\":[{\"name\":\"@method\",\"value\":\"POST\"}]}"));
    invalid(post("/internal/v1/http-signature/verify", "{\"keyId\":\"http-sign-ec\",\"signature\":\"sig1=:AAAA:\","
        + "\"components\":[]}"));
    verifyNoInteractions(http);
  }
}
