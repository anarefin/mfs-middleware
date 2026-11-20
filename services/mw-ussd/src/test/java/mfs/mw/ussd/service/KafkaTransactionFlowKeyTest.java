package mfs.mw.ussd.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import mfs.mw.ussd.dto.EncryptedPin;
import mfs.mw.ussd.dto.FlowRequest;
import mfs.mw.ussd.dto.UssdSession;
import org.junit.jupiter.api.Test;

/**
 * The idempotency key of a USSD transaction (spec 7.3 rule 1): one per session and transaction.
 * An SMPP gateway numbers its sessions 1..128 and reuses the numbers (its_session_info), so the
 * session id alone repeats; a later session with the same number, number and transfer is a new
 * transaction, never a replay of the old one (found by the API suite, ticket 58).
 */
class KafkaTransactionFlowKeyTest {

  static final Instant T0 = Instant.parse("2026-11-06T09:00:00Z");
  static final EncryptedPin PIN = new EncryptedPin("PIN_TRANSPORT.v1", "Y2lwaGVy");

  static FlowRequest p2p(String sessionId, Instant startedAt) {
    UssdSession s = new UssdSession("gp", sessionId, "01711000001", "main", 1, "en", "x.send", List.of("main:1"),
        Map.of(), 0, List.of(), false, startedAt);
    return new FlowRequest("P2P", s, "main:1", Map.of("creditParty", "01711000002", "amount", "10"), PIN);
  }

  @Test
  void theSameSessionAlwaysDerivesTheSameKey() {
    // a transfer's key does not depend on the attempt's txnId: a retry (e.g. after a wrong PIN) keeps it
    assertThat(KafkaTransactionFlow.idempotencyKey(p2p("smpp-7-ab", T0), "t-1"))
        .isEqualTo(KafkaTransactionFlow.idempotencyKey(p2p("smpp-7-ab", T0), "t-2"));
  }

  @Test
  void aReusedSessionNumberStartedLaterIsANewTransaction() {
    assertThat(KafkaTransactionFlow.idempotencyKey(p2p("smpp-7-ab", T0.plusSeconds(3600)), "t-2"))
        .isNotEqualTo(KafkaTransactionFlow.idempotencyKey(p2p("smpp-7-ab", T0), "t-1"));
  }

  /** ADR D26: a balance check maps no fields; each check (each txnId) is a new inquiry. */
  @Test
  void everyBalanceCheckOfOneSessionHasItsOwnKey() {
    UssdSession s = new UssdSession("gp", "smpp-7-ab", "01711000001", "main", 3, "en", "bal.check",
        List.of("main:5", "bal.pin"), Map.of(), 0, List.of(), false, T0);
    FlowRequest balance = new FlowRequest("BALANCE", s, "main:5>bal.pin", Map.of(), PIN);
    assertThat(KafkaTransactionFlow.idempotencyKey(balance, "t-1"))
        .isNotEqualTo(KafkaTransactionFlow.idempotencyKey(balance, "t-2"))
        .isEqualTo(KafkaTransactionFlow.idempotencyKey(balance, "t-1"));
  }

  @Test
  void twoBankAccountsOfOneSessionAreDifferentAddMoneyKeys() {
    assertThat(KafkaTransactionFlow.idempotencyKey(addMoney("1234567890123"), "t-1"))
        .isNotEqualTo(KafkaTransactionFlow.idempotencyKey(addMoney("1234567890124"), "t-1"));
  }

  static FlowRequest addMoney(String account) {
    UssdSession s = new UssdSession("gp", "smpp-7-ab", "01711000001", "main", 4, "en", "am.send",
        List.of("main:7"), Map.of(), 0, List.of(), false, T0);
    return new FlowRequest("ADD_MONEY_BANK", s, "main:7",
        Map.of("debitParty", account, "amount", "500.00", "partnerId", "c2wbank"), PIN);
  }
}
