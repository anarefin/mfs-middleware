package mfs.mw.adapter.bank.partner.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import mfs.mw.adapter.bank.partner.AuthScheme;
import mfs.mw.adapter.bank.partner.CredentialRef;
import mfs.mw.adapter.bank.partner.CredentialResolver;
import mfs.mw.adapter.bank.partner.InboundCall;
import mfs.mw.adapter.bank.partner.OutboundCall;
import mfs.mw.adapter.bank.partner.config.PartnerConfig;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The shipped auth schemes, built from partner config as the framework builds them: what
 * {@code apply} puts on an outbound request, and what {@code verify} accepts on an inbound one.
 * Secrets are obviously fake.
 */
class AuthSchemesTest {

  static final Instant NOW = Instant.parse("2026-11-04T10:00:00Z");
  static final CredentialRef REF = new CredentialRef("partners.acme.secret");
  static final CredentialResolver SECRETS =
      ref -> {
        if (!REF.equals(ref)) {
          throw new IllegalStateException("no secret stored under " + ref.path());
        }
        return "fake-secret-not-real";
      };

  static AuthScheme scheme(String name, Map<String, String> params, Clock clock) {
    return AuthSchemes.create(new PartnerConfig.Auth(name, REF.path(), params), SECRETS, clock);
  }

  static OutboundCall post(String body) {
    return new OutboundCall(
        "POST", URI.create("https://partner.invalid/api/v1/pay?x=1"), body.getBytes(StandardCharsets.UTF_8));
  }

  static InboundCall inbound(Map<String, String> headers, String body) {
    return new InboundCall("acme", "POST", "/partner/acme/callback", headers, body.getBytes(StandardCharsets.UTF_8));
  }

  @Nested
  class ApiKey {

    final AuthScheme scheme = scheme("api-key", Map.of(), Clock.systemUTC());

    @Test
    void applyPutsTheKeyInTheDefaultHeader() {
      OutboundCall call = post("{}");
      scheme.apply(call, REF);
      assertThat(call.headers()).containsEntry("X-Api-Key", "fake-secret-not-real");
    }

    @Test
    void theHeaderNameIsAParameter() {
      OutboundCall call = post("{}");
      scheme("api-key", Map.of("header", "Authorization-Key"), Clock.systemUTC()).apply(call, REF);
      assertThat(call.headers()).containsEntry("Authorization-Key", "fake-secret-not-real").doesNotContainKey("X-Api-Key");
    }

    @Test
    void verifyAcceptsTheRightKeyInAnyHeaderCase() {
      assertThat(scheme.verify(inbound(Map.of("x-api-key", "fake-secret-not-real"), "{}"), REF)).isTrue();
    }

    @Test
    void verifyRejectsAWrongOrMissingKey() {
      assertThat(scheme.verify(inbound(Map.of("X-Api-Key", "fake-secret-not-reaL"), "{}"), REF)).isFalse();
      assertThat(scheme.verify(inbound(Map.of(), "{}"), REF)).isFalse();
    }

    @Test
    void applyFailsWhenNoSecretIsStored() {
      assertThatThrownBy(() -> scheme.apply(post("{}"), new CredentialRef("partners.other")))
          .isInstanceOf(IllegalStateException.class);
    }
  }

  @Nested
  class HmacSha256 {

    final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    final AuthScheme scheme = scheme("hmac-sha256", Map.of("key-id", "k1"), clock);

    @Test
    void applySignsMethodPathTimestampAndBodyHash() {
      OutboundCall call = post("{\"amount\":\"20.00\"}");
      scheme.apply(call, REF);

      assertThat(call.headers())
          .containsEntry("X-Timestamp", Long.toString(NOW.getEpochSecond()))
          .containsEntry("X-Key-Id", "k1");
      // golden vector, computed outside Java:
      // printf 'POST\n/api/v1/pay?x=1\n1793786400\n<sha256 hex of body>' \
      //   | openssl dgst -sha256 -hmac 'fake-secret-not-real' -binary | base64
      assertThat(call.headers()).containsEntry("X-Signature", "S3cqEh/Ov4Ku6eczDZwHuGcPRFSM8rv28bXZ+RSi3WA=");
    }

    @Test
    void verifyAcceptsWhatApplySignedForTheSameRequest() {
      String body = "{\"reference\":\"BITS1\",\"status\":\"SUCCESS\"}";
      Map<String, String> headers = signedHeaders("POST", "/partner/acme/callback", body);

      assertThat(scheme.verify(inbound(headers, body), REF)).isTrue();
    }

    @Test
    void verifyRejectsATamperedBody() {
      Map<String, String> headers = signedHeaders("POST", "/partner/acme/callback", "{\"status\":\"FAILED\"}");

      assertThat(scheme.verify(inbound(headers, "{\"status\":\"SUCCESS\"}"), REF)).isFalse();
    }

    @Test
    void verifyRejectsAStaleTimestamp() {
      String body = "{}";
      Map<String, String> headers = signedHeaders("POST", "/partner/acme/callback", body);
      AuthScheme later = scheme("hmac-sha256", Map.of("key-id", "k1"), Clock.fixed(NOW.plusSeconds(301), ZoneOffset.UTC));

      assertThat(later.verify(inbound(headers, body), REF)).isFalse();
    }

    @Test
    void verifyRejectsAWrongKeyIdAndMissingHeaders() {
      String body = "{}";
      Map<String, String> headers = new HashMap<>(signedHeaders("POST", "/partner/acme/callback", body));
      headers.put("X-Key-Id", "k2");
      assertThat(scheme.verify(inbound(headers, body), REF)).isFalse();
      assertThat(scheme.verify(inbound(Map.of(), body), REF)).isFalse();
      assertThat(scheme.verify(inbound(Map.of("X-Signature", "x", "X-Timestamp", "soon", "X-Key-Id", "k1"), body), REF))
          .isFalse();
    }

    private Map<String, String> signedHeaders(String method, String path, String body) {
      OutboundCall call =
          new OutboundCall(method, URI.create("https://mw.invalid" + path), body.getBytes(StandardCharsets.UTF_8));
      scheme.apply(call, REF);
      return call.headers();
    }
  }

  @Test
  void noAuthAddsNothingAndNeverVerifiesAnInboundCall() {
    AuthScheme none = scheme("none", Map.of(), Clock.systemUTC());
    OutboundCall call = post("{}");
    none.apply(call, CredentialRef.NONE);
    assertThat(call.headers()).isEmpty();
    assertThat(none.verify(inbound(Map.of("X-Api-Key", "anything"), "{}"), CredentialRef.NONE)).isFalse();
  }

  @Test
  void anUnknownSchemeIsRefused() {
    assertThatThrownBy(() -> scheme("basic", Map.of(), Clock.systemUTC())).isInstanceOf(IllegalArgumentException.class);
  }
}
