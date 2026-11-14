package mfs.mw.crypto.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import mfs.mw.crypto.client.Capability;
import mfs.mw.crypto.client.KeyAlgorithm;
import mfs.mw.crypto.exception.MwException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Spec 18.8 key hierarchy as amended 2026-11-14 (JWT_SIGN is RSA, RS256). */
class KeyPurposeTest {

  @ParameterizedTest
  @CsvSource({
    "PIN_TRANSPORT, RSA_3072, PIN_HMAC, pin-transport, RSA-OAEP-256, enc",
    "PIN_HMAC, HMAC_SHA256, PIN_HMAC, pin-hmac, ,",
    "JWT_SIGN, RSA_3072, JWS, jwt-sign, RS256, sig",
    "ASSERTION_SIGN, EC_P256, JWS, assertion-sign, ES256, sig",
    "AUDIT_ANCHOR_SIGN, EC_P256, JWS, audit-anchor-sign, ES256, sig",
    "TOKENISE_MSISDN, HMAC_SHA256, TOKENISE, tokenise-msisdn, ,",
    "KEK_COLUMN, AES_256, ENVELOPE, kek-column, ,",
    "HMAC_WEBHOOK, HMAC_SHA256, HMAC, hmac-webhook, ,",
    "XMLDSIG, RSA_3072, XMLDSIG, xmldsig, RS256, sig",
    "ZPK, TDES_2KEY, PIN_BLOCK, zpk, ,",
    "TPK, TDES_2KEY, PIN_BLOCK, tpk, ,",
    "ZPK_AES, AES_128, PIN_BLOCK, zpk-aes, ,",
    "TPK_AES, AES_128, PIN_BLOCK, tpk-aes, ,",
    "PVK, TDES_2KEY, PVV, pvk, ,"
  })
  void eachPurposeHasItsAlgorithmCapabilityAndDefaultKeyId(
      String name, KeyAlgorithm algorithm, Capability capability, String keyId, String jwkAlg, String use) {
    KeyPurpose p = KeyPurpose.parse(name);
    assertThat(p.name()).isEqualTo(name);
    assertThat(p.algorithm()).isEqualTo(algorithm);
    assertThat(p.capability()).isEqualTo(capability);
    assertThat(p.defaultKeyId()).isEqualTo(keyId);
    assertThat(p.jwkAlgorithm()).isEqualTo(jwkAlg);
    assertThat(p.jwkUse()).isEqualTo(use);
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "jwt_sign", "TOKENISE_", "KEK_", "TOKENISE_msisdn", "SIGN", "KEK_A-B", "HMAC_", "HMAC_a",
      "TOKENISE_ABCDEFGHIJKLMNOPQRSTUVWXYZ", "ZMK", "PVK_AES"})
  void anythingElseIsAnUnknownPurpose(String name) {
    assertThatThrownBy(() -> KeyPurpose.parse(name))
        .isInstanceOfSatisfying(MwException.class, e -> assertThat(e.code()).isEqualTo("MW-VAL-011"));
  }

  @Test
  void nullIsAnUnknownPurpose() {
    assertThatThrownBy(() -> KeyPurpose.parse(null)).isInstanceOf(MwException.class);
  }
}
