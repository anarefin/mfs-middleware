package mfs.mw.adapter.telco.mapper;

import static mfs.mw.adapter.telco.TelcoFixtures.newTxnId;
import static mfs.mw.adapter.telco.TelcoFixtures.pay;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Optional;
import mfs.mw.adapter.telco.dto.PartnerRequest;
import mfs.mw.adapter.telco.partner.PartnerCall;
import org.junit.jupiter.api.Test;

/** The canonical call the partner mappings read (amounts in minor units and as a major-unit string). */
class PartnerCallMapperTest {

  final PartnerCallMapper mapper = new PartnerCallMapper();

  @Test
  void aPayBecomesACanonicalCallWithOurReferenceTheAmountAndTheCreditedMsisdn() {
    PartnerRequest request = pay(newTxnId(), "01711000001", 2000);

    PartnerCall call = mapper.toCall(request, "UPAYREF1");

    assertThat(call.partnerId()).isEqualTo("gp");
    assertThat(call.operation()).isEqualTo("PAY");
    assertThat(call.txnType()).isEqualTo("TOPUP");
    assertThat(call.txnId()).isEqualTo(request.txnId());
    assertThat(call.correlationId()).isEqualTo("corr-0002");
    assertThat(call.reference()).isEqualTo("UPAYREF1");
    assertThat(call.input())
        .containsEntry("amount", Map.of("valueMinor", 2000L, "currency", "BDT", "major", "20.00"))
        .containsEntry("creditParty", Map.of("type", "MSISDN", "id", "01711000001"))
        .containsEntry("partner", Map.of("partnerId", "gp", "productCode", "PREPAID"));
  }

  @Test
  void theMajorUnitAmountKeepsTheFractionalPart() {
    assertThat(major(12305)).isEqualTo("123.05");
    assertThat(major(5)).isEqualTo("0.05");
  }

  @Test
  void aStatusQueryCarriesOnlyWhatItHas() {
    PartnerCall call = mapper.toCall(mfs.mw.adapter.telco.TelcoFixtures.statusQuery(newTxnId()), "R");
    assertThat(call.operation()).isEqualTo("STATUS_QUERY");
    assertThat(call.reference()).isEqualTo("R");
  }

  private String major(long minor) {
    Object amount = mapper.toCall(pay(newTxnId(), "01711000001", minor), "R").input().get("amount");
    return (String) ((Map<?, ?>) amount).get("major");
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
