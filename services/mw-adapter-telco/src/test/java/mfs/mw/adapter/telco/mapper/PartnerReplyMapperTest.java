package mfs.mw.adapter.telco.mapper;

import static mfs.mw.adapter.telco.TelcoFixtures.NOW;
import static mfs.mw.adapter.telco.TelcoFixtures.newTxnId;
import static mfs.mw.adapter.telco.TelcoFixtures.pay;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import mfs.mw.adapter.telco.TelcoFixtures;
import mfs.mw.adapter.telco.config.OcsProperties;
import mfs.mw.adapter.telco.dto.OcsResult;
import mfs.mw.adapter.telco.dto.PartnerReply;
import mfs.mw.adapter.telco.dto.PartnerRequest;
import mfs.mw.adapter.telco.exception.OcsNotSentException;
import mfs.mw.adapter.telco.exception.OcsUnknownOutcomeException;
import org.junit.jupiter.api.Test;

/** Expected codes are the contract's samples and the canonical code pattern of spec 5.3. */
class PartnerReplyMapperTest {

  static final OcsProperties PROPS =
      new OcsProperties(
          URI.create("http://ocs.invalid"),
          Duration.ofSeconds(1),
          Duration.ofSeconds(1),
          Set.of("9876"),
          Map.of("2001", new OcsProperties.ErrorMapping("SUBSCRIBER_BARRED", false)));

  final PartnerReplyMapper mapper =
      new PartnerReplyMapper(PROPS, Clock.fixed(NOW, ZoneOffset.UTC));
  final PartnerRequest request = pay(newTxnId(), "01711000001", 2000);

  @Test
  void successCarriesTheOcsReferenceAndOurStableReference() {
    PartnerReply reply = mapper.toReply(request, "UPAYREF1", new OcsResult.Accepted("OCS-1", "0000"));

    assertThat(reply.family()).isEqualTo("telco");
    assertThat(reply.requestId()).isEqualTo(request.requestId());
    assertThat(reply.txnId()).isEqualTo(request.txnId());
    assertThat(reply.correlationId()).isEqualTo("corr-0002");
    assertThat(reply.partnerId()).isEqualTo("gp");
    assertThat(reply.repliedAt()).isEqualTo(NOW);
    assertThat(reply.payload())
        .isEqualTo(
            new PartnerReply.Payload(
                "PAY", "SUCCESS", "OCS-1", "0000", null, null, null, Map.of("partnerTxnRef", "UPAYREF1")));
  }

  @Test
  void aStatusQueryAnswerKeepsItsOperation() {
    PartnerRequest query = TelcoFixtures.statusQuery(newTxnId());
    PartnerReply reply = mapper.toReply(query, "UPAYREF1", new OcsResult.Accepted("OCS-1", "0000"));
    assertThat(reply.payload().operation()).isEqualTo("STATUS_QUERY");
    assertThat(reply.payload().outcome()).isEqualTo("SUCCESS");
  }

  @Test
  void anOcsWithoutRecordOfTheTopupIsNotFound() {
    PartnerReply reply =
        mapper.toReply(TelcoFixtures.statusQuery(newTxnId()), "UPAYREF1", new OcsResult.NotFound("4040", "Unknown transaction"));
    assertThat(reply.payload().operation()).isEqualTo("STATUS_QUERY");
    assertThat(reply.payload().outcome()).isEqualTo("NOT_FOUND");
    assertThat(reply.payload().partnerCode()).isEqualTo("4040");
    assertThat(reply.payload().errorCode()).isNull();
  }

  @Test
  void aStatusQueryThatCouldNotBeSentIsUnknownNeverFailed() {
    // a FAILED answer to an inquiry would release a hold the partner may have used
    for (OcsNotSentException.Reason r : OcsNotSentException.Reason.values()) {
      PartnerReply reply =
          mapper.notSent(TelcoFixtures.statusQuery(newTxnId()), "R", new OcsNotSentException(r, null));
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
        mapper.toReply(request, "R", new OcsResult.Rejected("2001", "Subscriber barred"));

    assertThat(reply.payload().outcome()).isEqualTo("FAILED");
    assertThat(reply.payload().errorCode()).isEqualTo("MW-PTR-GP-SUBSCRIBER_BARRED");
    assertThat(reply.payload().partnerCode()).isEqualTo("2001");
    assertThat(reply.payload().message()).isEqualTo("Subscriber barred");
    assertThat(reply.payload().retryable()).isFalse();
  }

  @Test
  void anUnlistedRejectionCodeIsUnmappedAndNotRetryable() {
    PartnerReply reply = mapper.toReply(request, "R", new OcsResult.Rejected("HTTP_400", null));

    assertThat(reply.payload().errorCode()).isEqualTo("MW-PTR-GP-UNMAPPED");
    assertThat(reply.payload().retryable()).isFalse();
  }

  @Test
  void aPartnerIdWithOtherCharactersStillGivesAValidCode() {
    PartnerRequest odd =
        new PartnerRequest(
            1, "telco", request.requestId(), request.txnId(), "c", NOW, NOW,
            new PartnerRequest.Payload(
                "PAY", "TOPUP", new PartnerRequest.PartnerRef("robi-axiata", "PREPAID", null), "x",
                request.payload().amount(), null, request.payload().creditParty(), null, null));

    assertThat(mapper.toReply(odd, "R", new OcsResult.Rejected("2001", "m")).payload().errorCode())
        .isEqualTo("MW-PTR-ROBI_AXIATA-SUBSCRIBER_BARRED");
  }

  @Test
  void anAmbiguousCodeIsUnknownAndNeverRetryable() {
    PartnerReply reply = mapper.toReply(request, "R", new OcsResult.Ambiguous("9876", "odd"));

    assertThat(reply.payload().outcome()).isEqualTo("UNKNOWN");
    assertThat(reply.payload().errorCode()).isEqualTo("MW-TMO-PARTNER_AMBIGUOUS_CODE");
    assertThat(reply.payload().partnerCode()).isEqualTo("9876");
    assertThat(reply.payload().retryable()).isFalse();
  }

  @Test
  void aTopupThatWasNotSentIsAFailedRetryableSystemError() {
    assertCode(notSent(OcsNotSentException.Reason.CIRCUIT_OPEN), "MW-SYS-PARTNER_CIRCUIT_OPEN");
    assertCode(notSent(OcsNotSentException.Reason.BULKHEAD_FULL), "MW-SYS-PARTNER_BUSY");
    assertCode(notSent(OcsNotSentException.Reason.CONNECT_FAILED), "MW-SYS-PARTNER_UNAVAILABLE");
    assertThat(notSent(OcsNotSentException.Reason.CIRCUIT_OPEN).payload().outcome())
        .isEqualTo("FAILED");
    assertThat(notSent(OcsNotSentException.Reason.CIRCUIT_OPEN).payload().retryable()).isTrue();
  }

  @Test
  void everyUnknownReasonGivesATimeoutNamespaceCode() {
    for (OcsUnknownOutcomeException.Reason reason : OcsUnknownOutcomeException.Reason.values()) {
      PartnerReply reply =
          mapper.unknown(request, "R", new OcsUnknownOutcomeException(reason, null));
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

  private PartnerReply notSent(OcsNotSentException.Reason reason) {
    return mapper.notSent(request, "R", new OcsNotSentException(reason, null));
  }

  private static void assertCode(PartnerReply reply, String code) {
    assertThat(reply.payload().errorCode()).isEqualTo(code);
  }
}
