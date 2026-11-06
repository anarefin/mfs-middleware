package mfs.mw.adapter.bank.mapper;

import static mfs.mw.adapter.bank.BankFixtures.NOW;
import static mfs.mw.adapter.bank.BankFixtures.newTxnId;
import static mfs.mw.adapter.bank.BankFixtures.pay;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Map;
import mfs.mw.adapter.bank.BankFixtures;
import mfs.mw.adapter.bank.dto.PartnerReply;
import mfs.mw.adapter.bank.dto.PartnerRequest;
import mfs.mw.adapter.bank.partner.PartnerResponse;
import mfs.mw.adapter.bank.partner.PartnerResponse.NotSentReason;
import mfs.mw.adapter.bank.partner.PartnerResponse.UnknownReason;
import org.junit.jupiter.api.Test;

/** Expected codes are the contract's samples and the canonical code pattern of spec 5.3. */
class PartnerReplyMapperTest {

  final PartnerReplyMapper mapper = new PartnerReplyMapper(Clock.fixed(NOW, ZoneOffset.UTC));
  final PartnerRequest request = pay(newTxnId(), "01711000001", 2000);

  @Test
  void successCarriesTheOcsReferenceAndOurStableReference() {
    PartnerReply reply = mapper.toReply(request, "BITSREF1", new PartnerResponse.Success("OCS-1", "0000"));

    assertThat(reply.family()).isEqualTo("bank");
    assertThat(reply.requestId()).isEqualTo(request.requestId());
    assertThat(reply.txnId()).isEqualTo(request.txnId());
    assertThat(reply.correlationId()).isEqualTo("corr-0002");
    assertThat(reply.partnerId()).isEqualTo("c2wbank");
    assertThat(reply.repliedAt()).isEqualTo(NOW);
    assertThat(reply.payload())
        .isEqualTo(
            new PartnerReply.Payload(
                "PAY", "SUCCESS", "OCS-1", "0000", null, null, null, Map.of("partnerTxnRef", "BITSREF1")));
  }

  @Test
  void aStatusQueryAnswerKeepsItsOperation() {
    PartnerRequest query = BankFixtures.statusQuery(newTxnId());
    PartnerReply reply = mapper.toReply(query, "BITSREF1", new PartnerResponse.Success("OCS-1", "0000"));
    assertThat(reply.payload().operation()).isEqualTo("STATUS_QUERY");
    assertThat(reply.payload().outcome()).isEqualTo("SUCCESS");
  }

  @Test
  void anOcsWithoutRecordOfTheTopupIsNotFound() {
    PartnerReply reply =
        mapper.toReply(BankFixtures.statusQuery(newTxnId()), "BITSREF1", new PartnerResponse.NotFound("4040", "Unknown transaction"));
    assertThat(reply.payload().operation()).isEqualTo("STATUS_QUERY");
    assertThat(reply.payload().outcome()).isEqualTo("NOT_FOUND");
    assertThat(reply.payload().partnerCode()).isEqualTo("4040");
    assertThat(reply.payload().errorCode()).isNull();
  }

  @Test
  void aStatusQueryThatCouldNotBeSentIsUnknownNeverFailed() {
    // a FAILED answer to an inquiry would release a hold the partner may have used
    for (NotSentReason r : NotSentReason.values()) {
      PartnerReply reply =
          mapper.toReply(BankFixtures.statusQuery(newTxnId()), "R", new PartnerResponse.NotSent(r));
      assertThat(reply.payload().outcome()).as(r.name()).isEqualTo("UNKNOWN");
      assertThat(reply.payload().errorCode()).isEqualTo("MW-TMO-PARTNER_" + r.name());
    }
  }

  @Test
  void anExpiredPayIsFailedAsNotSent() {
    PartnerReply reply = mapper.expired(request);
    assertThat(reply.payload().outcome()).isEqualTo("FAILED");
    assertThat(reply.payload().errorCode()).isEqualTo("MW-SYS-PARTNER_REQUEST_EXPIRED");
    assertThat(reply.payload().retryable()).isTrue();
  }

  @Test
  void aMappedRejectionIsFailedWithTheCanonicalPartnerCode() {
    PartnerReply reply =
        mapper.toReply(request, "R", new PartnerResponse.Rejected("2001", "Subscriber barred", "SUBSCRIBER_BARRED", false));

    assertThat(reply.payload().outcome()).isEqualTo("FAILED");
    assertThat(reply.payload().errorCode()).isEqualTo("MW-PTR-C2WBANK-SUBSCRIBER_BARRED");
    assertThat(reply.payload().partnerCode()).isEqualTo("2001");
    assertThat(reply.payload().message()).isEqualTo("Subscriber barred");
    assertThat(reply.payload().retryable()).isFalse();
  }

  @Test
  void anUnlistedRejectionCodeIsUnmappedAndNotRetryable() {
    PartnerReply reply = mapper.toReply(request, "R", new PartnerResponse.Rejected("HTTP_400", null, "UNMAPPED", false));

    assertThat(reply.payload().errorCode()).isEqualTo("MW-PTR-C2WBANK-UNMAPPED");
    assertThat(reply.payload().retryable()).isFalse();
  }

  @Test
  void aPartnerIdWithOtherCharactersStillGivesAValidCode() {
    PartnerRequest odd =
        new PartnerRequest(
            1, "bank", request.requestId(), request.txnId(), "c", NOW, NOW,
            new PartnerRequest.Payload(
                "PAY", "TOPUP", new PartnerRequest.PartnerRef("robi-axiata", "PREPAID", null), "x",
                request.payload().amount(), null, request.payload().creditParty(), null, null));

    assertThat(mapper.toReply(odd, "R", new PartnerResponse.Rejected("2001", "m", "SUBSCRIBER_BARRED", false)).payload().errorCode())
        .isEqualTo("MW-PTR-ROBI_AXIATA-SUBSCRIBER_BARRED");
  }

  @Test
  void anAmbiguousCodeIsUnknownAndNeverRetryable() {
    PartnerReply reply = mapper.toReply(request, "R", new PartnerResponse.Ambiguous("9876", "odd"));

    assertThat(reply.payload().outcome()).isEqualTo("UNKNOWN");
    assertThat(reply.payload().errorCode()).isEqualTo("MW-TMO-PARTNER_AMBIGUOUS_CODE");
    assertThat(reply.payload().partnerCode()).isEqualTo("9876");
    assertThat(reply.payload().retryable()).isFalse();
  }

  @Test
  void aTopupThatWasNotSentIsAFailedRetryableSystemError() {
    assertCode(notSent(NotSentReason.CIRCUIT_OPEN), "MW-SYS-PARTNER_CIRCUIT_OPEN");
    assertCode(notSent(NotSentReason.BULKHEAD_FULL), "MW-SYS-PARTNER_BUSY");
    assertCode(notSent(NotSentReason.CONNECT_FAILED), "MW-SYS-PARTNER_UNAVAILABLE");
    assertCode(notSent(NotSentReason.DISABLED), "MW-SYS-PARTNER_DISABLED");
    assertCode(notSent(NotSentReason.MISCONFIGURED), "MW-SYS-PARTNER_MISCONFIGURED");
    for (NotSentReason r : NotSentReason.values()) {
      assertThat(notSent(r).payload().outcome()).isEqualTo("FAILED");
      assertThat(notSent(r).payload().retryable()).isTrue();
    }
  }

  @Test
  void everyUnknownReasonGivesATimeoutNamespaceCode() {
    for (UnknownReason reason : UnknownReason.values()) {
      PartnerReply reply = mapper.toReply(request, "R", new PartnerResponse.Unknown(reason));
      assertThat(reply.payload().outcome()).isEqualTo("UNKNOWN");
      assertThat(reply.payload().errorCode()).isEqualTo("MW-TMO-PARTNER_" + reason.name());
    }
  }

  @Test
  void anInvalidRequestIsFailedAsAValidationError() {
    PartnerReply reply = mapper.invalid(request, "amount must be positive");

    assertThat(reply.payload().outcome()).isEqualTo("FAILED");
    assertThat(reply.payload().errorCode()).isEqualTo("MW-VAL-INVALID_PARTNER_REQUEST");
    assertThat(reply.payload().message()).isEqualTo("amount must be positive");
    assertThat(reply.payload().retryable()).isFalse();
  }

  @Test
  void anAcknowledgedAsyncRequestIsPendingWithThePartnerReference() {
    PartnerReply reply = mapper.toReply(request, "R", new PartnerResponse.Pending("P-1", "0001"));
    assertThat(reply.payload().outcome()).isEqualTo("PENDING");
    assertThat(reply.payload().partnerRef()).isEqualTo("P-1");
    assertThat(reply.payload().errorCode()).isNull();
  }

  private PartnerReply notSent(NotSentReason reason) {
    return mapper.toReply(request, "R", new PartnerResponse.NotSent(reason));
  }

  private static void assertCode(PartnerReply reply, String code) {
    assertThat(reply.payload().errorCode()).isEqualTo(code);
  }
}
