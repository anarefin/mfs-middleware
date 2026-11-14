package mfs.mw.crypto.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import java.util.List;
import mfs.mw.crypto.dto.Envelope;
import mfs.mw.crypto.dto.EnvelopeEncryptRequest;
import mfs.mw.crypto.dto.HmacSignRequest;
import mfs.mw.crypto.dto.HmacVerifyRequest;
import mfs.mw.crypto.dto.HmacView;
import mfs.mw.crypto.dto.TokeniseRequest;
import mfs.mw.crypto.dto.TokensView;
import mfs.mw.crypto.entity.KeyState;
import mfs.mw.crypto.exception.MwException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** HMAC, tokenisation and envelope encryption above the provider SPI (spec 18.8). */
class OtherOperationsTest {

  static final String DATA = Base64.getEncoder().encodeToString("webhook body".getBytes());

  OperationFixture f;
  HmacService hmac;
  TokenService tokens;
  EnvelopeService envelopes;

  @BeforeEach
  void setUp() {
    f = new OperationFixture()
        .key("hmac-webhook", 1, "HMAC_WEBHOOK", KeyState.VERIFY_ONLY)
        .key("hmac-webhook", 2, "HMAC_WEBHOOK", KeyState.ACTIVE)
        .key("pin-hmac", 1, "PIN_HMAC", KeyState.ACTIVE)
        .key("tokenise-msisdn", 1, "TOKENISE_MSISDN", KeyState.ACTIVE)
        .key("kek-column", 1, "KEK_COLUMN", KeyState.VERIFY_ONLY)
        .key("kek-column", 2, "KEK_COLUMN", KeyState.ACTIVE);
    f.hsm.putHmacKey("tokenise-msisdn.v1", new byte[32]);
    hmac = new HmacService(f.keys);
    tokens = new TokenService(f.keys);
    envelopes = new EnvelopeService(f.keys);
  }

  static String code(Throwable e) {
    return ((MwException) e).code();
  }

  @Test
  void hmacSignAndVerifyAcceptTheCurrentAndThePreviousVersion() {
    HmacView signed = hmac.sign(new HmacSignRequest("hmac-webhook", DATA));
    assertThat(signed.kid()).isEqualTo("hmac-webhook.v2");
    assertThat(hmac.verify(new HmacVerifyRequest("hmac-webhook", DATA, signed.mac())))
        .isEqualTo(HmacView.verified(true, "hmac-webhook.v2"));

    HmacView old = hmac.sign(new HmacSignRequest("hmac-webhook.v2", DATA));
    assertThat(old.mac()).isEqualTo(signed.mac());
    String v1 = Base64.getEncoder().encodeToString(f.hsm.hmac("hmac-webhook.v1", List.of("webhook body".getBytes())).getFirst());
    assertThat(hmac.verify(new HmacVerifyRequest("hmac-webhook", DATA, v1)))
        .isEqualTo(HmacView.verified(true, "hmac-webhook.v1"));
    assertThat(hmac.verify(new HmacVerifyRequest("hmac-webhook.v2", DATA, v1)).valid()).isFalse();

    String other = Base64.getEncoder().encodeToString("other".getBytes());
    assertThat(hmac.verify(new HmacVerifyRequest("hmac-webhook", other, signed.mac())).valid()).isFalse();
  }

  @Test
  void hmacNeverAnswersWithThePinOrTokenKeys() {
    assertThatThrownBy(() -> hmac.sign(new HmacSignRequest("pin-hmac", DATA)))
        .satisfies(e -> assertThat(code(e)).isEqualTo("MW-VAL-015"));
    assertThatThrownBy(() -> hmac.sign(new HmacSignRequest("tokenise-msisdn", DATA)))
        .satisfies(e -> assertThat(code(e)).isEqualTo("MW-VAL-015"));
    assertThatThrownBy(() -> hmac.sign(new HmacSignRequest("hmac-webhook.v1", DATA)))
        .satisfies(e -> assertThat(code(e)).isEqualTo("MW-VAL-015"));
  }

  @Test
  void tokensAreTheDomainAndTheKnownBase32Hmac() {
    TokensView t = tokens.tokenise(new TokeniseRequest(TokeniseRequest.Domain.MSISDN, List.of("01712345678", "01712345678", "01812345678")));
    assertThat(t.kid()).isEqualTo("tokenise-msisdn.v1");
    // HMAC-SHA256, key 32 zero bytes, over "01712345678" (Python hmac + base64.b32encode, padding dropped)
    assertThat(t.tokens().getFirst()).isEqualTo("MSISDN_XAFFUJ3SO7Y7J7GY7RVTMOEG2PJWYEPYYR4QPJI422K5JEO265SA");
    assertThat(t.tokens().get(1)).isEqualTo(t.tokens().getFirst());
    assertThat(t.tokens().get(2)).isNotEqualTo(t.tokens().getFirst()).startsWith("MSISDN_").hasSize(7 + 52);
    assertThatThrownBy(() -> tokens.tokenise(new TokeniseRequest(TokeniseRequest.Domain.NID, List.of("1"))))
        .satisfies(e -> assertThat(code(e)).isEqualTo("MW-VAL-010"));
  }

  @Test
  void envelopeRoundTripsUnderTheActiveAndThePreviousKek() {
    String plain = Base64.getEncoder().encodeToString("1990123456789".getBytes());
    String aad = Base64.getEncoder().encodeToString("customer.nid".getBytes());
    Envelope e = envelopes.encrypt(new EnvelopeEncryptRequest(null, "KEK_COLUMN", plain, aad));
    assertThat(e.kekId()).isEqualTo("kek-column.v2");
    assertThat(Base64.getDecoder().decode(e.iv())).hasSize(12);
    assertThat(Base64.getDecoder().decode(e.ciphertext())).hasSize(13 + 16);
    assertThat(envelopes.decrypt(e).plaintext()).isEqualTo(plain);

    Envelope old = envelopes.encrypt(new EnvelopeEncryptRequest("kek-column.v2", null, plain, null));
    assertThat(old.wrappedKey()).isNotEqualTo(e.wrappedKey());
    assertThat(envelopes.decrypt(old).plaintext()).isEqualTo(plain);

    byte[] wrappedV1 = f.hsm.wrapKey("kek-column.v1", new byte[32]);
    // an envelope under the VERIFY_ONLY version still opens, a new one cannot be made with it
    assertThatThrownBy(() -> envelopes.encrypt(new EnvelopeEncryptRequest("kek-column.v1", null, plain, null)))
        .satisfies(x -> assertThat(code(x)).isEqualTo("MW-VAL-015"));
    assertThat(f.hsm.unwrapKey("kek-column.v1", wrappedV1)).hasSize(32);
  }

  @Test
  void aTamperedEnvelopeIsRefused() {
    String plain = Base64.getEncoder().encodeToString("secret".getBytes());
    Envelope e = envelopes.encrypt(new EnvelopeEncryptRequest(null, "KEK_COLUMN", plain, null));
    byte[] ct = Base64.getDecoder().decode(e.ciphertext());
    ct[0] ^= 1;
    Envelope flipped = new Envelope(e.kekId(), e.wrappedKey(), e.iv(), Base64.getEncoder().encodeToString(ct), null);
    assertThatThrownBy(() -> envelopes.decrypt(flipped)).satisfies(x -> assertThat(code(x)).isEqualTo("MW-VAL-016"));

    Envelope otherAad = new Envelope(e.kekId(), e.wrappedKey(), e.iv(), e.ciphertext(), "AAAA");
    assertThatThrownBy(() -> envelopes.decrypt(otherAad)).satisfies(x -> assertThat(code(x)).isEqualTo("MW-VAL-016"));

    byte[] wk = Base64.getDecoder().decode(e.wrappedKey());
    wk[wk.length - 1] ^= 1;
    Envelope badKey = new Envelope(e.kekId(), Base64.getEncoder().encodeToString(wk), e.iv(), e.ciphertext(), null);
    assertThatThrownBy(() -> envelopes.decrypt(badKey)).satisfies(x -> assertThat(code(x)).isEqualTo("MW-VAL-016"));

    Envelope wrongKek = new Envelope("kek-column.v1", e.wrappedKey(), e.iv(), e.ciphertext(), null);
    assertThatThrownBy(() -> envelopes.decrypt(wrongKek)).satisfies(x -> assertThat(code(x)).isEqualTo("MW-VAL-016"));
  }
}
