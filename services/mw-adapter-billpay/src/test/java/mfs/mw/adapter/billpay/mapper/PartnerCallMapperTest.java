package mfs.mw.adapter.billpay.mapper;

import static mfs.mw.adapter.billpay.BillpayFixtures.newTxnId;
import static mfs.mw.adapter.billpay.BillpayFixtures.pay;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Optional;
import mfs.mw.adapter.billpay.dto.PartnerRequest;
import mfs.mw.adapter.billpay.partner.PartnerCall;
import org.junit.jupiter.api.Test;

/** The canonical call the partner mappings read (amounts in minor units and as a major-unit string). */
class PartnerCallMapperTest {

  final PartnerCallMapper mapper = new PartnerCallMapper();

  @Test
  void aPayBecomesACanonicalCallWithOurReferenceTheAmountTheBillAndTheBiller() {
    PartnerRequest request = pay(newTxnId(), "1234567", 2000);

    PartnerCall call = mapper.toCall(request, "BITSREF1");

    assertThat(call.partnerId()).isEqualTo("billagg");
    assertThat(call.operation()).isEqualTo("PAY");
    assertThat(call.txnType()).isEqualTo("BILL_PAY");
    assertThat(call.txnId()).isEqualTo(request.txnId());
    assertThat(call.correlationId()).isEqualTo("corr-0002");
    assertThat(call.reference()).isEqualTo("BITSREF1");
    assertThat(call.input())
        .containsEntry("amount", Map.of("valueMinor", 2000L, "currency", "BDT", "major", "20.00"))
        .containsEntry("creditParty", Map.of("type", "BILLER_ACCOUNT", "id", "1234567"))
        .containsEntry("partner", Map.of("partnerId", "billagg", "productCode", "BILL", "billerCode", "DESCO"));
  }

  @Test
  void theMajorUnitAmountKeepsTheFractionalPart() {
    assertThat(major(12305)).isEqualTo("123.05");
    assertThat(major(5)).isEqualTo("0.05");
  }

  @Test
  void aStatusQueryCarriesOnlyWhatItHas() {
    PartnerCall call = mapper.toCall(mfs.mw.adapter.billpay.BillpayFixtures.statusQuery(newTxnId()), "R");
    assertThat(call.operation()).isEqualTo("STATUS_QUERY");
    assertThat(call.reference()).isEqualTo("R");
  }

  private String major(long minor) {
    Object amount = mapper.toCall(pay(newTxnId(), "1234567", minor), "R").input().get("amount");
    return (String) ((Map<?, ?>) amount).get("major");
  }

  @Test
  void aWellFormedPayRequestHasNoProblem() {
    assertThat(mapper.problem(pay(newTxnId(), "1234567", 2000))).isEmpty();
  }

  @Test
  void aStatusQueryNeedsOnlyItsPartner() {
    PartnerRequest q = mfs.mw.adapter.billpay.BillpayFixtures.statusQuery(newTxnId());
    assertThat(mapper.problem(q)).isEmpty();
    PartnerRequest.Payload p = q.payload();
    PartnerRequest bare =
        withPayload(q, new PartnerRequest.Payload("STATUS_QUERY", "BILL_PAY", p.partner(), "x", null, null, null, null, null));
    assertThat(mapper.problem(bare)).isEmpty();
    PartnerRequest noPartner =
        withPayload(q, new PartnerRequest.Payload("STATUS_QUERY", "BILL_PAY", null, "x", null, null, null, null, null));
    assertThat(mapper.problem(noPartner)).contains("partner is required");
  }

  @Test
  void reportsWhatIsWrongWithARequestThatCannotBeSent() {
    PartnerRequest ok = pay(newTxnId(), "1234567", 2000);

    assertThat(mapper.problem(withOperation(ok, "REVERSE"))).contains("unsupported operation");
    assertThat(mapper.problem(withFamily(ok, "telco"))).contains("unsupported family");
    assertThat(mapper.problem(pay(newTxnId(), "1234567", 0))).contains("amount must be positive");
    assertThat(mapper.problem(pay(newTxnId(), "12 34", 100))).contains("credit party must be a biller account");
    assertThat(mapper.problem(withPayload(ok, null))).isNotEqualTo(Optional.empty());
    PartnerRequest.Payload p = ok.payload();
    PartnerRequest noBiller =
        withPayload(
            ok,
            new PartnerRequest.Payload(
                "PAY", p.txnType(), new PartnerRequest.PartnerRef("billagg", "BILL", null), p.partnerTxnRef(),
                p.amount(), p.debitParty(), p.creditParty(), null, null));
    assertThat(mapper.problem(noBiller)).contains("partner.billerCode is required");
  }

  @Test
  void aBillFetchIsAnInquiryCallForTheBillersAggregatorWithoutReference() {
    PartnerCall call = mapper.fetchCall("billagg", "DESCO", "1234567", "corr-f");
    assertThat(call.partnerId()).isEqualTo("billagg");
    assertThat(call.operation()).isEqualTo("INQUIRY");
    assertThat(call.txnType()).isEqualTo("BILL_PAY");
    assertThat(call.reference()).isNull();
    assertThat(call.correlationId()).isEqualTo("corr-f");
    assertThat(call.input())
        .containsEntry("creditParty", Map.of("type", "BILLER_ACCOUNT", "id", "1234567"))
        .containsEntry("partner", Map.of("partnerId", "billagg", "billerCode", "DESCO"));
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
