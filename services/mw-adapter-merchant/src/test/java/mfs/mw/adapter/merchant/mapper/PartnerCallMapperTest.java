package mfs.mw.adapter.merchant.mapper;

import static mfs.mw.adapter.merchant.MerchantFixtures.ACCOUNT;
import static mfs.mw.adapter.merchant.MerchantFixtures.newTxnId;
import static mfs.mw.adapter.merchant.MerchantFixtures.pay;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Optional;
import mfs.mw.adapter.merchant.MerchantFixtures;
import mfs.mw.adapter.merchant.dto.PartnerRequest;
import mfs.mw.adapter.merchant.partner.PartnerCall;
import org.junit.jupiter.api.Test;

/** The canonical call the partner mappings read (amounts in minor units and as a major-unit string). */
class PartnerCallMapperTest {

  final PartnerCallMapper mapper = new PartnerCallMapper();

  @Test
  void aNotifyBecomesACanonicalCallWithOurReferenceTheAmountTheWalletAndTheMerchant() {
    PartnerRequest request = pay(newTxnId(), ACCOUNT, 2000);

    PartnerCall call = mapper.toCall(request, "BITSREF1");

    assertThat(call.partnerId()).isEqualTo("c2wbank");
    assertThat(call.operation()).isEqualTo("PAY");
    assertThat(call.txnType()).isEqualTo("MERCHANT_PAY");
    assertThat(call.txnId()).isEqualTo(request.txnId());
    assertThat(call.correlationId()).isEqualTo("corr-0002");
    assertThat(call.reference()).isEqualTo("BITSREF1");
    assertThat(call.input())
        .containsEntry("amount", Map.of("valueMinor", 2000L, "currency", "BDT", "major", "20.00"))
        .containsEntry("debitParty", Map.of("type", "WALLET", "id", "01711000001", "walletType", "CUSTOMER"))
        .containsEntry("creditParty", Map.of("type", "MERCHANT", "id", ACCOUNT))
        .containsEntry("partner", Map.of("partnerId", "c2wbank", "productCode", "C2W"));
  }

  @Test
  void theMajorUnitAmountKeepsTheFractionalPart() {
    assertThat(major(12305)).isEqualTo("123.05");
    assertThat(major(5)).isEqualTo("0.05");
  }

  @Test
  void aStatusQueryCarriesOnlyWhatItHas() {
    PartnerCall call = mapper.toCall(MerchantFixtures.statusQuery(newTxnId()), "R");
    assertThat(call.operation()).isEqualTo("STATUS_QUERY");
    assertThat(call.reference()).isEqualTo("R");
  }

  private String major(long minor) {
    Object amount = mapper.toCall(pay(newTxnId(), ACCOUNT, minor), "R").input().get("amount");
    return (String) ((Map<?, ?>) amount).get("major");
  }

  @Test
  void aWellFormedPayRequestHasNoProblem() {
    assertThat(mapper.problem(pay(newTxnId(), ACCOUNT, 2000))).isEmpty();
  }

  @Test
  void aStatusQueryNeedsOnlyItsPartner() {
    PartnerRequest q = MerchantFixtures.statusQuery(newTxnId());
    assertThat(mapper.problem(q)).isEmpty();
    PartnerRequest.Payload p = q.payload();
    PartnerRequest bare =
        withPayload(q, new PartnerRequest.Payload("STATUS_QUERY", "MERCHANT_PAY", p.partner(), "x", null, null, null, null, null));
    assertThat(mapper.problem(bare)).isEmpty();
    PartnerRequest noPartner =
        withPayload(q, new PartnerRequest.Payload("STATUS_QUERY", "MERCHANT_PAY", null, "x", null, null, null, null, null));
    assertThat(mapper.problem(noPartner)).contains("partner is required");
  }

  @Test
  void reportsWhatIsWrongWithARequestThatCannotBeSent() {
    PartnerRequest ok = pay(newTxnId(), ACCOUNT, 2000);
    PartnerRequest.Payload p = ok.payload();

    assertThat(mapper.problem(withOperation(ok, "SCREEN"))).contains("unsupported operation");
    assertThat(mapper.problem(withFamily(ok, "billpay"))).contains("unsupported family");
    assertThat(mapper.problem(pay(newTxnId(), ACCOUNT, 0))).contains("amount must be positive");
    assertThat(mapper.problem(withPayload(ok, null))).isNotEqualTo(Optional.empty());
    assertThat(mapper.problem(withPayload(ok, new PartnerRequest.Payload(
            "PAY", "P2P", p.partner(), p.partnerTxnRef(), p.amount(), p.debitParty(), p.creditParty(), null, null))))
        .contains("unsupported txnType");
    assertThat(mapper.problem(withPayload(ok, new PartnerRequest.Payload(
            "PAY", p.txnType(), p.partner(), p.partnerTxnRef(), p.amount(), p.debitParty(),
            new PartnerRequest.Party("WALLET", "01711000001", null, null), null, null))))
        .contains("credit party must be a merchant");
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

  @Test
  void aReverseNeedsItsPartnerAndAPositiveAmount() {
    PartnerRequest ok = pay(newTxnId(), ACCOUNT, 2000);
    PartnerRequest.Payload p = ok.payload();
    PartnerRequest reverse =
        withPayload(ok, new PartnerRequest.Payload("REVERSE", p.txnType(), p.partner(), null, p.amount(), null, p.creditParty(), "PTR-1", null));
    assertThat(mapper.problem(reverse)).isEmpty();
    assertThat(mapper.toCall(reverse, "BITS-REF").operation()).isEqualTo("REVERSE");
    assertThat(mapper.toCall(reverse, "BITS-REF").reference()).isEqualTo("BITS-REF");
    PartnerRequest noAmount =
        withPayload(ok, new PartnerRequest.Payload("REVERSE", p.txnType(), p.partner(), null, null, null, p.creditParty(), null, null));
    assertThat(mapper.problem(noAmount)).contains("amount must be positive");
  }

  @Test
  void aValidateIsAnInquiryForTheMerchantAndItsTillWithoutAnAmount() {
    PartnerRequest ok = pay(newTxnId(), ACCOUNT, 2000);
    PartnerRequest.Payload p = ok.payload();
    PartnerRequest validate = withPayload(ok, new PartnerRequest.Payload(
        "INQUIRY", p.txnType(), p.partner(), null, null, null, p.creditParty(), null, Map.of("tillId", "TILL-01")));
    assertThat(mapper.problem(validate)).isEmpty();
    PartnerCall call = mapper.toCall(validate, "BITS-REF");
    assertThat(call.operation()).isEqualTo("INQUIRY");
    assertThat(call.input()).containsEntry("attributes", Map.of("tillId", "TILL-01"))
        .containsEntry("creditParty", Map.of("type", "MERCHANT", "id", ACCOUNT));
    PartnerRequest noMerchant = withPayload(ok, new PartnerRequest.Payload(
        "INQUIRY", p.txnType(), p.partner(), null, null, null, null, null, null));
    assertThat(mapper.problem(noMerchant)).contains("credit party must be a merchant");
  }

  private static PartnerRequest withPayload(PartnerRequest r, PartnerRequest.Payload p) {
    return new PartnerRequest(
        r.schemaVersion(), r.family(), r.requestId(), r.txnId(), r.correlationId(), r.issuedAt(),
        r.deadlineAt(), p);
  }
}
