package mfs.mw.checkout.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import mfs.mw.checkout.TestInfrastructure;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/** {@code checkout.merchant_nonce} (ticket 99) on real PostgreSQL: once per merchant and window. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(TestInfrastructure.class)
class MerchantNonceRepositoryIT {

  static final Duration WINDOW = Duration.ofSeconds(630);

  private final MerchantNonceRepository nonces;

  @Autowired
  MerchantNonceRepositoryIT(MerchantNonceRepository nonces) {
    this.nonces = nonces;
  }

  @Test
  void aNonceIsUsedOncePerMerchantWithinTheWindow() {
    String nonce = UUID.randomUUID().toString();
    Instant now = Instant.parse("2026-11-19T10:00:00Z");

    assertThat(nonces.firstUse("merchant_demo", nonce, now, WINDOW)).isTrue();
    assertThat(nonces.firstUse("merchant_demo", nonce, now.plusSeconds(10), WINDOW)).isFalse();
    assertThat(nonces.firstUse("merchant_fashion", nonce, now, WINDOW)).isTrue();
    assertThat(nonces.firstUse("merchant_demo", nonce, now.plus(WINDOW).plusSeconds(1), WINDOW))
        .as("forgotten once its date can no longer be accepted")
        .isTrue();
  }
}
