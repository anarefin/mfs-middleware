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
  static final String PAYER = "01711000001";

  private final SessionRepository sessions;

  @Autowired
  SessionRepositoryIT(SessionRepository sessions) {
    this.sessions = sessions;
  }

  static CheckoutSession fresh(String orderId, Instant expiresAt) {
    return new CheckoutSession(
        UUID.randomUUID(), "tok-" + UUID.randomUUID(), "merchant_demo", "01911000001", orderId, 1000, "BDT",
        null, "bn", "https://s.example.test/ok", "https://s.example.test/fail", "https://s.example.test/cancel",
        "https://s.example.test/ipn", SessionStatus.OPEN, null, 0, null, null, NOW, expiresAt, NOW);
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
    assertThat(sessions.otpRequested(s.sessionId(), PAYER, 3, NOW)).isTrue();

    UUID first = UUID.randomUUID();
    assertThat(sessions.claimForPayment(s.sessionId(), PAYER, first, NOW)).isTrue();
    assertThat(sessions.claimForPayment(s.sessionId(), PAYER, UUID.randomUUID(), NOW)).isFalse();

    assertThat(sessions.finish(UUID.randomUUID(), SessionStatus.PAID, null, NOW)).isFalse();
    assertThat(sessions.finish(first, SessionStatus.PAID, null, NOW)).isTrue();
    assertThat(sessions.finish(first, SessionStatus.FAILED, "MW-SYS-001", NOW)).as("terminal stays").isFalse();
    assertThat(sessions.findByTxnId(first).orElseThrow().status()).isEqualTo(SessionStatus.PAID);
  }

  @Test
  void parallelClaimsLetExactlyOnePaymentThrough() throws Exception {
    CheckoutSession s = fresh("ORD-" + UUID.randomUUID(), NOW.plusSeconds(900));
    sessions.insert(s);
    sessions.otpRequested(s.sessionId(), PAYER, 3, NOW);

    int threads = 24;
    java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.atomic.AtomicInteger won = new java.util.concurrent.atomic.AtomicInteger();
    try (var pool = java.util.concurrent.Executors.newFixedThreadPool(threads)) {
      var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
      for (int i = 0; i < threads; i++) {
        futures.add(pool.submit(() -> {
          start.await();
          if (sessions.claimForPayment(s.sessionId(), PAYER, UUID.randomUUID(), NOW)) {
            won.incrementAndGet();
          }
          return null;
        }));
      }
      start.countDown();
      for (var f : futures) {
        f.get();
      }
    }
    assertThat(won.get()).isEqualTo(1);
  }

  @Test
  void aClaimNeedsTheWalletTheLastOtpWentTo() {
    CheckoutSession s = fresh("ORD-" + UUID.randomUUID(), NOW.plusSeconds(900));
    sessions.insert(s);
    assertThat(sessions.claimForPayment(s.sessionId(), PAYER, UUID.randomUUID(), NOW)).as("no OTP asked").isFalse();
    sessions.otpRequested(s.sessionId(), PAYER, 3, NOW);
    sessions.otpRequested(s.sessionId(), "01711000002", 3, NOW);
    assertThat(sessions.claimForPayment(s.sessionId(), PAYER, UUID.randomUUID(), NOW)).isFalse();
    assertThat(sessions.findById(s.sessionId()).orElseThrow().payerMsisdn()).isEqualTo("01711000002");
  }

  @Test
  void anExpiredSessionTakesNoOtpNoClaimAndNoCancel() {
    CheckoutSession s = fresh("ORD-" + UUID.randomUUID(), NOW.minusSeconds(1));
    sessions.insert(s);
    assertThat(sessions.otpRequested(s.sessionId(), PAYER, 3, NOW)).isFalse();
    assertThat(sessions.claimForPayment(s.sessionId(), PAYER, UUID.randomUUID(), NOW)).isFalse();
    assertThat(sessions.cancel(s.sessionId(), NOW)).isFalse();
  }

  @Test
  void theOtpSendLimitHolds() {
    CheckoutSession s = fresh("ORD-" + UUID.randomUUID(), NOW.plusSeconds(900));
    sessions.insert(s);
    assertThat(sessions.otpRequested(s.sessionId(), PAYER, 2, NOW)).isTrue();
    assertThat(sessions.otpRequested(s.sessionId(), PAYER, 2, NOW)).isTrue();
    assertThat(sessions.otpRequested(s.sessionId(), PAYER, 2, NOW)).isFalse();
    assertThat(sessions.findById(s.sessionId()).orElseThrow().otpSends()).isEqualTo(2);
  }
}
