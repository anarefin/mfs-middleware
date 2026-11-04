package mfs.mw.adapter.telco.mapper;

import static mfs.mw.adapter.telco.TelcoFixtures.newTxnId;
import static mfs.mw.adapter.telco.TelcoFixtures.pay;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import mfs.mw.adapter.telco.dto.OcsTopupRequest;
import mfs.mw.adapter.telco.dto.PartnerRequest;
import org.junit.jupiter.api.Test;

class OcsRequestMapperTest {

  final OcsRequestMapper mapper = new OcsRequestMapper();

  @Test
  void mapsMinorUnitsToAMajorUnitDecimalStringAndTheCreditedMsisdn() {
    OcsTopupRequest ocs = mapper.toOcs(pay(newTxnId(), "01711000001", 2000), "UPAYREF1");

    assertThat(ocs).isEqualTo(new OcsTopupRequest("UPAYREF1", "01711000001", "20.00"));
  }

  @Test
  void keepsTheFractionalPart() {
    assertThat(mapper.toOcs(pay(newTxnId(), "01711000001", 12305), "R").amount())
        .isEqualTo("123.05");
    assertThat(mapper.toOcs(pay(newTxnId(), "01711000001", 5), "R").amount()).isEqualTo("0.05");
  }

  @Test
  void aWellFormedPayRequestHasNoProblem() {
    assertThat(mapper.problem(pay(newTxnId(), "01711000001", 2000))).isEmpty();
  }

  @Test
  void aStatusQueryNeedsOnlyItsPartner() {
    PartnerRequest q = mfs.mw.adapter.telco.TelcoFixtures.statusQuery(newTxnId());
    assertThat(mapper.problem(q)).isEmpty();
    PartnerRequest.Payload p = q.payload();
    PartnerRequest bare =
        withPayload(q, new PartnerRequest.Payload("STATUS_QUERY", "TOPUP", p.partner(), "x", null, null, null, null, null));
    assertThat(mapper.problem(bare)).isEmpty();
    PartnerRequest noPartner =
        withPayload(q, new PartnerRequest.Payload("STATUS_QUERY", "TOPUP", null, "x", null, null, null, null, null));
    assertThat(mapper.problem(noPartner)).contains("partner is required");
  }

  @Test
  void reportsWhatIsWrongWithARequestThatCannotBeSent() {
    PartnerRequest ok = pay(newTxnId(), "01711000001", 2000);

    assertThat(mapper.problem(withOperation(ok, "REVERSE"))).contains("unsupported operation");
    assertThat(mapper.problem(withFamily(ok, "billpay"))).contains("unsupported family");
    assertThat(mapper.problem(pay(newTxnId(), "01711000001", 0))).contains("amount must be positive");
    assertThat(mapper.problem(pay(newTxnId(), "not-a-number", 100))).contains("credit party must be an MSISDN");
    assertThat(mapper.problem(withPayload(ok, null))).isNotEqualTo(Optional.empty());
  }

  private static PartnerRequest withOperation(PartnerRequest r, String op) {
    PartnerRequest.Payload p = r.payload();
    return withPayload(
        r,
        new PartnerRequest.Payload(
            op, p.txnType(), p.partner(), p.partnerTxnRef(), p.amount(), p.debitParty(),
            p.creditParty(), p.originalPartnerRef(), p.attributes()));
  }

  private static PartnerRequest withFamily(PartnerRequest r, String family) {
    return new PartnerRequest(
        r.schemaVersion(), family, r.requestId(), r.txnId(), r.correlationId(), r.issuedAt(),
        r.deadlineAt(), r.payload());
  }

  private static PartnerRequest withPayload(PartnerRequest r, PartnerRequest.Payload p) {
    return new PartnerRequest(
        r.schemaVersion(), r.family(), r.requestId(), r.txnId(), r.correlationId(), r.issuedAt(),
        r.deadlineAt(), p);
  }
}
