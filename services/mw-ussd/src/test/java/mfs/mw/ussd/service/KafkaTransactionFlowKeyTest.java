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
  static final EncryptedPin PIN = new EncryptedPin("core-pin-dev-1", "Y2lwaGVy");

  static FlowRequest p2p(String sessionId, Instant startedAt) {
    UssdSession s = new UssdSession("gp", sessionId, "01711000001", "main", 1, "en", "x.send", List.of("main:1"),
        Map.of(), 0, List.of(), false, startedAt);
    return new FlowRequest("P2P", s, "main:1", Map.of("creditParty", "01711000002", "amount", "10"), PIN);
  }

  @Test
  void theSameSessionAlwaysDerivesTheSameKey() {
    assertThat(KafkaTransactionFlow.idempotencyKey(p2p("smpp-7-ab", T0)))
        .isEqualTo(KafkaTransactionFlow.idempotencyKey(p2p("smpp-7-ab", T0)));
  }

  @Test
  void aReusedSessionNumberStartedLaterIsANewTransaction() {
    assertThat(KafkaTransactionFlow.idempotencyKey(p2p("smpp-7-ab", T0.plusSeconds(3600))))
        .isNotEqualTo(KafkaTransactionFlow.idempotencyKey(p2p("smpp-7-ab", T0)));
  }
}
