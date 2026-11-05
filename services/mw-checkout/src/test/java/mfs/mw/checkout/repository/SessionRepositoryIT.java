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
    assertThat(sessions.claimForPayment(s.sessionId(), "h1", first, 3, NOW)).isTrue();
    assertThat(sessions.claimForPayment(s.sessionId(), "h1", UUID.randomUUID(), 3, NOW)).isFalse();

    assertThat(sessions.finish(UUID.randomUUID(), SessionStatus.PAID, null, NOW)).isFalse();
    assertThat(sessions.finish(first, SessionStatus.PAID, null, NOW)).isTrue();
    assertThat(sessions.finish(first, SessionStatus.FAILED, "MW-SYS-001", NOW)).as("terminal stays").isFalse();
    assertThat(sessions.findByTxnId(first).orElseThrow().status()).isEqualTo(SessionStatus.PAID);
  }

  @Test
  void concurrentOtpChecksReserveNoMoreThanTheMaximumAndTheExhaustedOtpCannotBeClaimed() throws Exception {
    CheckoutSession s = fresh("ORD-" + UUID.randomUUID(), NOW.plusSeconds(900));
    sessions.insert(s);
    sessions.otpIssued(s.sessionId(), "01711000001", "h1", NOW.plusSeconds(120), 3, NOW);

    int threads = 24;
    java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.atomic.AtomicInteger reserved = new java.util.concurrent.atomic.AtomicInteger();
    try (var pool = java.util.concurrent.Executors.newFixedThreadPool(threads)) {
      var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
      for (int i = 0; i < threads; i++) {
        futures.add(pool.submit(() -> {
          start.await();
          if (sessions.reserveOtpAttempt(s.sessionId(), "h1", 3, NOW)) {
            reserved.incrementAndGet();
          }
          return null;
        }));
      }
      start.countDown();
      for (var f : futures) {
        f.get();
      }
    }

    assertThat(reserved.get()).as("OTP checks allowed").isEqualTo(3);
    assertThat(sessions.findById(s.sessionId()).orElseThrow().otpAttempts()).isEqualTo(3);
    sessions.voidOtpIfExhausted(s.sessionId(), "h1", 3, NOW);
    assertThat(sessions.findById(s.sessionId()).orElseThrow().otpHash()).isNull();
    assertThat(sessions.claimForPayment(s.sessionId(), "h1", UUID.randomUUID(), 3, NOW)).isFalse();
  }

  @Test
  void aClaimNeedsAnAttemptWithinTheMaximum() {
    CheckoutSession s = fresh("ORD-" + UUID.randomUUID(), NOW.plusSeconds(900));
    sessions.insert(s);
    sessions.otpIssued(s.sessionId(), "01711000001", "h1", NOW.plusSeconds(120), 3, NOW);
    for (int i = 0; i < 3; i++) {
      sessions.reserveOtpAttempt(s.sessionId(), "h1", 3, NOW);
    }
    assertThat(sessions.claimForPayment(s.sessionId(), "h1", UUID.randomUUID(), 2, NOW)).isFalse();
    assertThat(sessions.claimForPayment(s.sessionId(), "h1", UUID.randomUUID(), 3, NOW))
        .as("the third check may still be the right one").isTrue();
  }

  @Test
  void anExpiredSessionTakesNoOtpNoClaimAndNoCancel() {
    CheckoutSession s = fresh("ORD-" + UUID.randomUUID(), NOW.minusSeconds(1));
    sessions.insert(s);
    assertThat(sessions.otpIssued(s.sessionId(), "01711000001", "h1", NOW.plusSeconds(120), 3, NOW)).isFalse();
    assertThat(sessions.claimForPayment(s.sessionId(), "h1", UUID.randomUUID(), 3, NOW)).isFalse();
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
