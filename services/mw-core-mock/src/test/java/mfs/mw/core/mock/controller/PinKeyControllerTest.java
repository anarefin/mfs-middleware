package mfs.mw.core.mock.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import mfs.mw.core.mock.security.PinKeyProvider;

/** Channels and tests fetch the public key that PINs must be encrypted with. */
@WebMvcTest(PinKeyController.class)
class PinKeyControllerTest {

  private final MockMvcTester mvc;

  @MockitoBean PinKeyProvider keys;

  @Autowired
  PinKeyControllerTest(MockMvcTester mvc) {
    this.mvc = mvc;
  }

  @Test
  void exportsKeyIdAlgorithmAndPemPublicKey() {
    given(keys.keyId()).willReturn("core-pin-1");
    given(keys.publicKeyPem()).willReturn("-----BEGIN PUBLIC KEY-----\nAAAA\n-----END PUBLIC KEY-----\n");

    assertThat(mvc.get().uri("/core-mock/v1/pin-public-key"))
        .hasStatusOk()
        .bodyJson()
        .extractingPath("$.keyId").isEqualTo("core-pin-1");
    assertThat(mvc.get().uri("/core-mock/v1/pin-public-key"))
        .bodyJson()
        .extractingPath("$.algorithm").isEqualTo("RSA-OAEP-SHA-256");
    assertThat(mvc.get().uri("/core-mock/v1/pin-public-key"))
        .bodyJson()
        .extractingPath("$.publicKeyPem").asString().startsWith("-----BEGIN PUBLIC KEY-----");
  }
}
