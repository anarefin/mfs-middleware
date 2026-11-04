package mfs.mw.checkout.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import mfs.mw.checkout.TestInfrastructure;
import mfs.mw.checkout.dto.SessionStatus;
import mfs.mw.checkout.entity.CheckoutSession;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/** {@code checkout.session} on real PostgreSQL: the conditional state changes cannot be won twice. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(TestInfrastructure.class)
class SessionRepositoryIT {

  static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MILLIS);

  private final SessionRepository sessions;

  @Autowired
  SessionRepositoryIT(SessionRepository sessions) {
    this.sessions = sessions;
  }

  static CheckoutSession fresh(String orderId, Instant expiresAt) {
    return new CheckoutSession(
        UUID.randomUUID(), "tok-" + UUID.randomUUID(), "merchant_demo", "01911000001", orderId, 1000, "BDT",
        null, "bn", "https://s.example.test/ok", "https://s.example.test/fail", "https://s.example.test/cancel",
        "https://s.example.test/ipn", SessionStatus.OPEN, null, null, null, 0, 0, null, null, NOW, expiresAt, NOW);
  }

  @Test
  void aSessionIsStoredOncePerMerchantOrderAndReadBack() {
    String order = "ORD-" + UUID.randomUUID();
    CheckoutSession s = fresh(order, NOW.plusSeconds(900));
    assertThat(sessions.insert(s)).isTrue();
    assertThat(sessions.insert(fresh(order, NOW.plusSeconds(900)))).isFalse();

    CheckoutSession read = sessions.findByToken(s.payToken()).orElseThrow();
    assertThat(read).isEqualTo(s);
    assertThat(sessions.findByMerchantOrder("merchant_demo", order)).contains(s);
  }

  @Test
  void onlyOneClaimWinsAndOnlyTheClaimedTxnFinishesTheSession() {
    CheckoutSession s = fresh("ORD-" + UUID.randomUUID(), NOW.plusSeconds(900));
    sessions.insert(s);
    assertThat(sessions.otpIssued(s.sessionId(), "01711000001", "h1", NOW.plusSeconds(120), 3, NOW)).isTrue();

    UUID first = UUID.randomUUID();
    assertThat(sessions.claimForPayment(s.sessionId(), "h1", first, NOW)).isTrue();
    assertThat(sessions.claimForPayment(s.sessionId(), "h1", UUID.randomUUID(), NOW)).isFalse();

    assertThat(sessions.finish(UUID.randomUUID(), SessionStatus.PAID, null, NOW)).isFalse();
    assertThat(sessions.finish(first, SessionStatus.PAID, null, NOW)).isTrue();
    assertThat(sessions.finish(first, SessionStatus.FAILED, "MW-SYS-001", NOW)).as("terminal stays").isFalse();
    assertThat(sessions.findByTxnId(first).orElseThrow().status()).isEqualTo(SessionStatus.PAID);
  }

  @Test
  void anExpiredSessionTakesNoOtpNoClaimAndNoCancel() {
    CheckoutSession s = fresh("ORD-" + UUID.randomUUID(), NOW.minusSeconds(1));
    sessions.insert(s);
    assertThat(sessions.otpIssued(s.sessionId(), "01711000001", "h1", NOW.plusSeconds(120), 3, NOW)).isFalse();
    assertThat(sessions.claimForPayment(s.sessionId(), "h1", UUID.randomUUID(), NOW)).isFalse();
    assertThat(sessions.cancel(s.sessionId(), NOW)).isFalse();
  }

  @Test
  void theOtpSendLimitHolds() {
    CheckoutSession s = fresh("ORD-" + UUID.randomUUID(), NOW.plusSeconds(900));
    sessions.insert(s);
    assertThat(sessions.otpIssued(s.sessionId(), "01711000001", "a", NOW.plusSeconds(120), 2, NOW)).isTrue();
    assertThat(sessions.otpIssued(s.sessionId(), "01711000001", "b", NOW.plusSeconds(120), 2, NOW)).isTrue();
    assertThat(sessions.otpIssued(s.sessionId(), "01711000001", "c", NOW.plusSeconds(120), 2, NOW)).isFalse();
  }
}
