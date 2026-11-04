package mfs.mw.orchestrator.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import mfs.mw.orchestrator.TestData;
import mfs.mw.orchestrator.dto.Amount;
import mfs.mw.orchestrator.dto.EncryptedPin;
import mfs.mw.orchestrator.dto.TxnRequest;
import mfs.mw.orchestrator.entity.TransactionRecord;
import mfs.mw.orchestrator.service.saga.Status;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class RequestMapperTest {

  private final RequestMapper mapper = new RequestMapper(JsonMapper.builder().build());

  @Test
  void theSameBusinessRequestHashesEqualWhateverTheTransportFieldsSay() {
    TxnRequest a = TestData.p2p(TestData.txnId(), "key-1", 5_000);
    TxnRequest b =
        new TxnRequest(
            1, TestData.txnId(), "key-1", "another-correlation", a.channel(), a.txnType(),
            a.initiator(), a.debitParty(), a.creditParty(), a.amount(), null, null,
            new EncryptedPin("core-pin-test-1", "a-different-ciphertext"), "bn", null, null,
            Instant.parse("2026-11-03T09:00:00Z"));
    assertThat(mapper.requestHash(a)).isEqualTo(mapper.requestHash(b)).hasSize(32);
  }

  @Test
  void aDifferentAmountOrTypeChangesTheHash() {
    TxnRequest a = TestData.p2p(TestData.txnId(), "key-1", 5_000);
    TxnRequest amount =
        new TxnRequest(1, a.txnId(), "key-1", a.correlationId(), a.channel(), a.txnType(),
            a.initiator(), a.debitParty(), a.creditParty(), Amount.bdt(5_001), null, null,
            a.encryptedPin(), null, null, null, a.requestedAt());
    TxnRequest type =
        new TxnRequest(1, a.txnId(), "key-1", a.correlationId(), a.channel(), "CASH_IN",
            a.initiator(), a.debitParty(), a.creditParty(), a.amount(), null, null,
            a.encryptedPin(), null, null, null, a.requestedAt());
    assertThat(mapper.requestHash(amount)).isNotEqualTo(mapper.requestHash(a));
    assertThat(mapper.requestHash(type)).isNotEqualTo(mapper.requestHash(a));
  }

  @Test
  void newTransactionStartsReceivedAndKeepsThePinApartFromTheStoredRequest() {
    TxnRequest req = TestData.p2p(TestData.txnId(), "key-1", 5_000);
    TransactionRecord rec = mapper.toRecord(req, "gw-1", TestData.NOW);
    assertThat(rec.status()).isEqualTo(Status.RECEIVED);
    assertThat(rec.previousStatus()).isNull();
    assertThat(rec.replyTo()).isEqualTo("gw-1");
    assertThat(rec.encryptedPin()).isEqualTo(req.encryptedPin());
    assertThat(rec.request().encryptedPin()).isNull();
    assertThat(rec.txnId().toString()).isEqualTo(req.txnId());
    assertThat(rec.createdAt()).isEqualTo(TestData.NOW);
  }
}
