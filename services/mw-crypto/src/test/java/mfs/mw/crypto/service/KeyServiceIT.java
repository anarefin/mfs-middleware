package mfs.mw.crypto.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import java.security.KeyPair;
import java.util.List;
import mfs.mw.crypto.TestInfrastructure;
import mfs.mw.crypto.client.FakeCryptoProvider;
import mfs.mw.crypto.dto.KeyView;
import mfs.mw.crypto.dto.PublicKeyView;
import mfs.mw.crypto.exception.MwException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Key management against the real {@code crypto.key} table (PostgreSQL) with an in-memory provider
 * in place of the HSM (spec 18.8): generate, rotate, public keys, fail-closed, bootstrap.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {"mw.crypto.pkcs11.enabled=false", "mw.crypto.bootstrap-keys=false"})
@Import({TestInfrastructure.class, KeyServiceIT.Fake.class})
class KeyServiceIT {

  @TestConfiguration(proxyBeanMethods = false)
  static class Fake {
    @Bean
    FakeCryptoProvider fakeCryptoProvider() {
      return new FakeCryptoProvider();
    }
  }

  final KeyService keys;
  final KeyBootstrapService bootstrap;
  final FakeCryptoProvider hsm;
  final JdbcClient jdbc;
  final MeterRegistry meters;

  @Autowired
  KeyServiceIT(
      KeyService keys, KeyBootstrapService bootstrap, FakeCryptoProvider hsm, JdbcClient jdbc, MeterRegistry meters) {
    this.keys = keys;
    this.bootstrap = bootstrap;
    this.hsm = hsm;
    this.jdbc = jdbc;
    this.meters = meters;
  }

  @BeforeEach
  void clean() {
    jdbc.sql("DELETE FROM key").update();
    hsm.clear();
    hsm.down(false);
  }

  static int code(Throwable e, String expected) {
    assertThat(e).isInstanceOf(MwException.class);
    assertThat(((MwException) e).code()).isEqualTo(expected);
    return ((MwException) e).status().value();
  }

  @Test
  void generatesVersionOneActiveUnderItsDefaultKeyId() {
    KeyView view = keys.generate("JWT_SIGN", null);

    assertThat(view.keyId()).isEqualTo("jwt-sign");
    assertThat(view.algorithm()).isEqualTo("RSA_3072");
    assertThat(view.provider()).isEqualTo("pkcs11");
    assertThat(view.versions()).singleElement().satisfies(v -> {
      assertThat(v.version()).isEqualTo(1);
      assertThat(v.kid()).isEqualTo("jwt-sign.v1");
      assertThat(v.state()).isEqualTo("ACTIVE");
    });
    assertThat(hsm.key("jwt-sign.v1")).isInstanceOf(KeyPair.class);
    assertThat(keys.list().items()).extracting(KeyView::keyId).containsExactly("jwt-sign");
    assertThat(meters.find("crypto.op.duration").tags("op", "generate", "provider", "pkcs11").timer().count())
        .isPositive();
  }

  @Test
  void aNamedKeyIdAndADomainPurpose() {
    KeyView view = keys.generate("TOKENISE_MSISDN", "tok-msisdn-2026");
    assertThat(view.keyId()).isEqualTo("tok-msisdn-2026");
    assertThat(view.algorithm()).isEqualTo("HMAC_SHA256");
    assertThat(hsm.labels()).containsExactly("tok-msisdn-2026.v1");
  }

  @Test
  void refusesAnExistingKeyIdAndAnUnknownPurpose() {
    keys.generate("PIN_HMAC", null);
    assertThat(code(catchThrown(() -> keys.generate("PIN_HMAC", null)), "MW-VAL-012")).isEqualTo(409);
    assertThat(code(catchThrown(() -> keys.generate("PIN_SOMETHING", null)), "MW-VAL-011")).isEqualTo(400);
    assertThat(hsm.labels()).containsExactly("pin-hmac.v1");
  }

  @Test
  void anHsmFailureRollsTheMetadataBackAndFailsClosed() {
    hsm.down(true);
    Throwable e = catchThrown(() -> keys.generate("ASSERTION_SIGN", null));
    assertThat(code(e, "MW-SYS-010")).isEqualTo(503);
    assertThat(((MwException) e).retryable()).isTrue();
    assertThat(keys.list().items()).isEmpty();
  }

  @Test
  void rotationAddsAVersionAndKeepsOnlyThePreviousForVerification() {
    keys.generate("ASSERTION_SIGN", null);
    keys.rotate("assertion-sign");
    KeyView third = keys.rotate("assertion-sign");

    assertThat(third.versions())
        .extracting(KeyView.Version::version, KeyView.Version::state)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(1, "RETIRED"),
            org.assertj.core.groups.Tuple.tuple(2, "VERIFY_ONLY"),
            org.assertj.core.groups.Tuple.tuple(3, "ACTIVE"));
    assertThat(third.versions().get(1).rotatedAt()).isNotNull();
    assertThat(third.versions().get(2).rotatedAt()).isNull();
    assertThat(keys.list().items().getFirst()).isEqualTo(third);
    assertThat(hsm.labels()).contains("assertion-sign.v3");

    PublicKeyView pub = keys.publicKeys("assertion-sign");
    assertThat(pub.versions()).extracting(PublicKeyView.Version::kid)
        .containsExactly("assertion-sign.v2", "assertion-sign.v3");
    assertThat((List<?>) pub.jwks().get("keys")).hasSize(2);
    assertThat(pub.versions().getLast().jwk()).containsEntry("alg", "ES256").containsEntry("crv", "P-256");
  }

  @Test
  void rotatingAnUnknownKeyAndAskingASymmetricKeyForItsPublicKeyAreRefused() {
    assertThat(code(catchThrown(() -> keys.rotate("nope")), "MW-VAL-010")).isEqualTo(404);
    assertThat(code(catchThrown(() -> keys.publicKeys("nope")), "MW-VAL-010")).isEqualTo(404);
    keys.generate("KEK_COLUMN", null);
    assertThat(code(catchThrown(() -> keys.publicKeys("kek-column")), "MW-VAL-013")).isEqualTo(400);
  }

  @Test
  void bootstrapCreatesTheMissingKeyOfEveryPurposeOnce() {
    keys.generate("JWT_SIGN", null);

    List<String> created = bootstrap.ensureKeys();

    assertThat(created).hasSize(11).doesNotContain("JWT_SIGN");
    assertThat(keys.list().items())
        .extracting(KeyView::purpose)
        .containsExactlyInAnyOrder(
            "PIN_TRANSPORT", "PIN_HMAC", "JWT_SIGN", "ASSERTION_SIGN", "AUDIT_ANCHOR_SIGN", "TOKENISE_MSISDN",
            "TOKENISE_NID", "TOKENISE_ACCOUNT", "KEK_COLUMN", "KEK_FILE", "XMLDSIG", "HMAC_ATMTOKEN");
    assertThat(bootstrap.ensureKeys()).isEmpty();
  }

  @Test
  void bootstrapStopsWhileTheHsmIsDownAndFinishesLater() {
    hsm.down(true);
    assertThat(bootstrap.ensureKeys()).isEmpty();
    assertThat(keys.list().items()).isEmpty();
    hsm.down(false);
    assertThat(bootstrap.ensureKeys()).hasSize(12);
  }

  private static Throwable catchThrown(Runnable r) {
    try {
      r.run();
    } catch (RuntimeException e) {
      return e;
    }
    throw new AssertionError("nothing thrown");
  }
}
