package mfs.mw.adapter.atm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import mfs.mw.adapter.atm.client.AtmTokenClient;
import mfs.mw.adapter.atm.config.AtmInboundProperties;
import mfs.mw.adapter.atm.dto.AtmTokenVerdict;
import mfs.mw.adapter.atm.exception.MwException;
import mfs.mw.adapter.atm.repository.InMemoryAtmInboundStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * Inbound ATM state (ticket 120b1, M9 review, FRS PLT-025, FL-11): cash fails closed until the
 * token check and the hold exist; one cash-out moves VERIFIED -> CONFIRMED | REVERSED once; a
 * repeat with another atmId, MSISDN or amount is an idempotency conflict. The token is checked by
 * mw-auth (ticket 120b2, mocked): a refused one is DECLINED and records nothing.
 */
class AtmInboundServiceTest {

  static final String MSISDN = "01711000001";
  static final String TOKEN = "654321";
  static final String WRONG = "111111";
  static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-20T12:00:00Z"), ZoneOffset.UTC);

  final InMemoryAtmInboundStore store = new InMemoryAtmInboundStore();
  final AtmTokenClient tokens = mock(AtmTokenClient.class);
  final AtmInboundService enabled = service(true);

  AtmInboundService service(Boolean cashEnabled) {
    return new AtmInboundService(
        store, new AtmInboundProperties("ucbatm", "ucbatm", "test-only-pw", cashEnabled), tokens, CLOCK);
  }

  @BeforeEach
  void tokens() {
    when(tokens.verify(anyString(), anyLong(), eq(TOKEN))).thenReturn(AtmTokenVerdict.accepted(MSISDN));
    when(tokens.verify(anyString(), anyLong(), eq(WRONG))).thenReturn(AtmTokenVerdict.declined("MW-AUTH-010"));
  }

  @Test
  void theTokenIsCheckedWithTheWalletAndAmountOnceAndARepeatIsNotCheckedAgain() {
    AtmInboundService.Ack first = enabled.verifyAndDebit("ATM-1", "UCB-1", MSISDN, TOKEN, 50_000);
    assertThat(first.status()).isEqualTo(AtmInboundService.ACCEPTED);
    assertThat(enabled.verifyAndDebit("ATM-1", "UCB-1", MSISDN, TOKEN, 50_000).txnId()).isEqualTo(first.txnId());
    verify(tokens, times(1)).verify(MSISDN, 50_000, TOKEN);
  }

  @Test
  void aRefusedTokenIsDeclinedAndRecordsNothing() {
    AtmInboundService.Ack ack = enabled.verifyAndDebit("ATM-1", "UCB-1", MSISDN, WRONG, 50_000);

    assertThat(ack.status()).isEqualTo(AtmInboundService.DECLINED);
    assertThat(ack.atmTxnRef()).isEqualTo("ATM-1");
    assertThat(ack.txnId()).isEmpty();
    assertThat(store.find(AtmInboundService.VERIFY, "ATM-1")).isEmpty();
    assertThatThrownBy(() -> enabled.confirmDispense("ATM-1", "UCB-1"))
        .isInstanceOfSatisfying(MwException.class, e -> assertThat(e.code()).isEqualTo(AtmInboundService.UNKNOWN_TXN));
  }

  @Test
  void aTokenOfTheWrongShapeIsInvalidAndNeverSent() {
    for (String bad : new String[] {null, "", "12345", "1234567", "12a456"}) {
      assertThatThrownBy(() -> enabled.verifyAndDebit("ATM-1", "UCB-1", MSISDN, bad, 50_000))
          .isInstanceOfSatisfying(MwException.class, e -> assertThat(e.code()).isEqualTo("MW-VAL-001"));
    }
    verifyNoInteractions(tokens);
  }

  @Test
  void anMwAuthOutageFailsClosedAndRecordsNothing() {
    when(tokens.verify(anyString(), anyLong(), eq("222222"))).thenThrow(
        new MwException(AtmTokenClient.UNAVAILABLE, "down").withStatus(HttpStatus.SERVICE_UNAVAILABLE));

    assertThatThrownBy(() -> enabled.verifyAndDebit("ATM-1", "UCB-1", MSISDN, "222222", 50_000))
        .isInstanceOfSatisfying(MwException.class, e -> assertThat(e.code()).isEqualTo(AtmTokenClient.UNAVAILABLE));
    assertThat(store.find(AtmInboundService.VERIFY, "ATM-1")).isEmpty();
  }

  @Test
  void verifyAndCashInFailClosedByDefault() {
    AtmInboundService closed = service(null);
    assertThatThrownBy(() -> closed.verifyAndDebit("ATM-1", "UCB-1", MSISDN, TOKEN, 50_000))
        .isInstanceOfSatisfying(MwException.class, e -> {
          assertThat(e.code()).isEqualTo(AtmInboundService.CASH_DISABLED);
          assertThat(e.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        });
    assertThatThrownBy(() -> closed.cashIn("ATM-2", "UCB-1", MSISDN, 50_000))
        .isInstanceOfSatisfying(MwException.class,
            e -> assertThat(e.code()).isEqualTo(AtmInboundService.CASH_DISABLED));
    assertThat(store.find(AtmInboundService.VERIFY, "ATM-1")).isEmpty();
    assertThat(store.find(AtmInboundService.CASH_IN, "ATM-2")).isEmpty();
    verify(tokens, never()).verify(anyString(), anyLong(), anyString());
  }

  @Test
  void aConfirmedCashOutRefusesALateReversalAndAnswersARepeatedConfirm() {
    String txnId = enabled.verifyAndDebit("ATM-1", "UCB-1", MSISDN, TOKEN, 50_000).txnId();
    assertThat(enabled.confirmDispense("ATM-1", "UCB-1").txnId()).isEqualTo(txnId);
    assertThat(enabled.confirmDispense("ATM-1", "UCB-1").txnId()).isEqualTo(txnId);
    assertThatThrownBy(() -> enabled.reversalAdvice("ATM-1", "UCB-1", "JAM"))
        .isInstanceOfSatisfying(MwException.class, e -> {
          assertThat(e.code()).isEqualTo(AtmInboundService.STATE_CONFLICT);
          assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
        });
    assertThat(store.find(AtmInboundService.VERIFY, "ATM-1").orElseThrow().state())
        .isEqualTo(AtmInboundService.CONFIRMED);
  }

  @Test
  void aReversedCashOutIsReversedOnceAndRefusesALateConfirm() {
    String txnId = enabled.verifyAndDebit("ATM-1", "UCB-1", MSISDN, TOKEN, 50_000).txnId();
    assertThat(enabled.reversalAdvice("ATM-1", "UCB-1", "JAM").txnId()).isEqualTo(txnId);
    assertThat(enabled.reversalAdvice("ATM-1", "UCB-1", "JAM").txnId()).isEqualTo(txnId);
    assertThatThrownBy(() -> enabled.confirmDispense("ATM-1", "UCB-1"))
        .isInstanceOfSatisfying(MwException.class,
            e -> assertThat(e.code()).isEqualTo(AtmInboundService.STATE_CONFLICT));
    assertThat(store.find(AtmInboundService.VERIFY, "ATM-1").orElseThrow().state())
        .isEqualTo(AtmInboundService.REVERSED);
  }

  @Test
  void anAdviceFromAnotherAtmIsAConflictAndChangesNothing() {
    enabled.verifyAndDebit("ATM-1", "UCB-1", MSISDN, TOKEN, 50_000);
    assertThatThrownBy(() -> enabled.confirmDispense("ATM-1", "UCB-2"))
        .isInstanceOfSatisfying(MwException.class,
            e -> assertThat(e.code()).isEqualTo(AtmInboundService.IDEM_CONFLICT));
    assertThatThrownBy(() -> enabled.reversalAdvice("ATM-1", "UCB-2", null))
        .isInstanceOfSatisfying(MwException.class,
            e -> assertThat(e.code()).isEqualTo(AtmInboundService.IDEM_CONFLICT));
    assertThat(store.find(AtmInboundService.VERIFY, "ATM-1").orElseThrow().state())
        .isEqualTo(AtmInboundService.VERIFIED);
  }

  @Test
  void aRepeatedVerifyOrCashInWithAnotherFingerprintIsAConflict() {
    String txnId = enabled.verifyAndDebit("ATM-1", "UCB-1", MSISDN, TOKEN, 50_000).txnId();
    assertThat(enabled.verifyAndDebit("ATM-1", "UCB-1", MSISDN, TOKEN, 50_000).txnId()).isEqualTo(txnId);
    Runnable[] replays = {
      () -> enabled.verifyAndDebit("ATM-1", "UCB-1", "01711000002", TOKEN, 50_000),
      () -> enabled.verifyAndDebit("ATM-1", "UCB-1", MSISDN, TOKEN, 50_001),
      () -> enabled.verifyAndDebit("ATM-1", "UCB-9", MSISDN, TOKEN, 50_000)
    };
    for (Runnable replay : replays) {
      assertThatThrownBy(replay::run)
          .isInstanceOfSatisfying(MwException.class, e -> {
            assertThat(e.code()).isEqualTo(AtmInboundService.IDEM_CONFLICT);
            assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
          });
    }
    enabled.cashIn("IN-1", "UCB-1", MSISDN, 25_000);
    assertThatThrownBy(() -> enabled.cashIn("IN-1", "UCB-1", MSISDN, 25_001))
        .isInstanceOfSatisfying(MwException.class,
            e -> assertThat(e.code()).isEqualTo(AtmInboundService.IDEM_CONFLICT));
  }

  @Test
  void theStoredFingerprintHoldsNoClearMsisdn() {
    enabled.verifyAndDebit("ATM-1", "UCB-1", MSISDN, TOKEN, 50_000);
    String fingerprint = store.find(AtmInboundService.VERIFY, "ATM-1").orElseThrow().fingerprint();
    assertThat(fingerprint).matches("[0-9a-f]{64}").doesNotContain(MSISDN.substring(1));
  }
}
