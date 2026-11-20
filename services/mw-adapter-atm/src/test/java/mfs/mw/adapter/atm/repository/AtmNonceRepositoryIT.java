package mfs.mw.adapter.atm.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;
import mfs.mw.adapter.atm.TestInfrastructure;
import mfs.mw.adapter.atm.partner.auth.CallbackNonces;
import mfs.mw.adapter.atm.security.NonceStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * WS-Security nonces of the ATM API against PostgreSQL (ticket 120b2, security fix on 120b1): the
 * ATM nonces have their own table and each row keeps its own expiry, so the partner callback purge
 * (a shorter window over {@code callback_nonce}) never frees an ATM nonce still inside its window.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(TestInfrastructure.class)
class AtmNonceRepositoryIT {

  private static final Duration ATM_WINDOW = Duration.ofSeconds(600);

  private final NonceStore atmNonces;
  private final AtmNonceRepository repo;
  private final CallbackNonces callbackNonces;

  @Autowired
  AtmNonceRepositoryIT(NonceStore atmNonces, AtmNonceRepository repo, CallbackNonces callbackNonces) {
    this.atmNonces = atmNonces;
    this.repo = repo;
    this.callbackNonces = callbackNonces;
  }

  @Test
  void aNonceIsAcceptedOnceWithinItsWindow() {
    String nonce = UUID.randomUUID().toString();
    assertThat(atmNonces.firstUse("ucbatm", nonce, ATM_WINDOW)).isTrue();
    assertThat(atmNonces.firstUse("ucbatm", nonce, ATM_WINDOW)).isFalse();
  }

  @Test
  void aPartnerCallbackPurgeDoesNotFreeALiveAtmNonce() throws Exception {
    String nonce = UUID.randomUUID().toString();
    assertThat(atmNonces.firstUse("ucbatm", nonce, ATM_WINDOW)).isTrue();
    Thread.sleep(20);

    // a partner callback with a far shorter window purges every callback row older than 1 ms
    callbackNonces.firstUse("someptr", UUID.randomUUID().toString(), Duration.ofMillis(1));

    assertThat(atmNonces.firstUse("ucbatm", nonce, ATM_WINDOW)).isFalse();
  }

  @Test
  void aShortWindowCallerOnlyPurgesRowsWhoseOwnExpiryPassed() throws Exception {
    String live = UUID.randomUUID().toString();
    String shortLived = UUID.randomUUID().toString();
    assertThat(repo.firstUse("ucbatm", live, ATM_WINDOW)).isTrue();
    assertThat(repo.firstUse("other", shortLived, Duration.ofMillis(5))).isTrue();
    Thread.sleep(20);

    // the next call purges the short-lived row only (expiry on the database clock)
    assertThat(repo.firstUse("other", shortLived, Duration.ofMillis(5))).isTrue();
    assertThat(repo.firstUse("ucbatm", live, ATM_WINDOW)).isFalse();
  }
}
