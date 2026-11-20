package mfs.mw.adapter.interop.mapper;

import static mfs.mw.adapter.interop.InteropFixtures.ACCOUNT;
import static mfs.mw.adapter.interop.InteropFixtures.BENEFICIARY;
import static mfs.mw.adapter.interop.InteropFixtures.PARTICIPANT;
import static mfs.mw.adapter.interop.InteropFixtures.newTxnId;
import static mfs.mw.adapter.interop.InteropFixtures.pay;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Optional;
import mfs.mw.adapter.interop.InteropFixtures;
import mfs.mw.adapter.interop.dto.PartnerRequest;
import mfs.mw.adapter.interop.partner.PartnerCall;
import org.junit.jupiter.api.Test;

/** The canonical call the partner mappings read (amounts in minor units and as a major-unit string). */
class PartnerCallMapperTest {

  final PartnerCallMapper mapper = new PartnerCallMapper();

  @Test
  void anNpsbTransferBecomesACanonicalCallWithOurReferenceTheAmountTheWalletAndTheBeneficiary() {
    PartnerRequest request = pay(newTxnId(), ACCOUNT, 2000);

    PartnerCall call = mapper.toCall(request, "BITSREF1");

    assertThat(call.partnerId()).isEqualTo("c2wbank");
    assertThat(call.operation()).isEqualTo("PAY");
    assertThat(call.txnType()).isEqualTo("NPSB_OUT");
    assertThat(call.txnId()).isEqualTo(request.txnId());
    assertThat(call.correlationId()).isEqualTo("corr-0002");
    assertThat(call.reference()).isEqualTo("BITSREF1");
    assertThat(call.input())
        .containsEntry("amount", Map.of("valueMinor", 2000L, "currency", "BDT", "major", "20.00"))
        .containsEntry("debitParty", Map.of("type", "WALLET", "id", ACCOUNT, "walletType", "CUSTOMER"))
        .containsEntry("creditParty", Map.of("type", "BANK_ACCOUNT", "id", BENEFICIARY))
        .containsEntry("partner", Map.of("partnerId", "c2wbank", "productCode", "NPSB"))
        .containsEntry("attributes", Map.of("participantId", PARTICIPANT));
  }

  @Test
  void theMajorUnitAmountKeepsTheFractionalPart() {
    assertThat(major(12305)).isEqualTo("123.05");
    assertThat(major(5)).isEqualTo("0.05");
  }

  @Test
  void aStatusQueryCarriesOnlyWhatItHas() {
    PartnerCall call = mapper.toCall(InteropFixtures.statusQuery(newTxnId()), "R");
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
    PartnerRequest q = InteropFixtures.statusQuery(newTxnId());
    assertThat(mapper.problem(q)).isEmpty();
    PartnerRequest.Payload p = q.payload();
    PartnerRequest bare =
        withPayload(q, new PartnerRequest.Payload("STATUS_QUERY", "NPSB_OUT", p.partner(), "x", null, null, null, null, null));
    assertThat(mapper.problem(bare)).isEmpty();
    PartnerRequest noPartner =
        withPayload(q, new PartnerRequest.Payload("STATUS_QUERY", "NPSB_OUT", null, "x", null, null, null, null, null));
    assertThat(mapper.problem(noPartner)).contains("partner is required");
  }

  @Test
  void reportsWhatIsWrongWithARequestThatCannotBeSent() {
    PartnerRequest ok = pay(newTxnId(), ACCOUNT, 2000);
    PartnerRequest.Payload p = ok.payload();

    assertThat(mapper.problem(withOperation(ok, "SCREEN"))).contains("unsupported operation");
    assertThat(mapper.problem(withOperation(ok, "INQUIRY"))).contains("unsupported operation");
    assertThat(mapper.problem(withFamily(ok, "billpay"))).contains("unsupported family");
    assertThat(mapper.problem(pay(newTxnId(), ACCOUNT, 0))).contains("amount must be positive");
    assertThat(mapper.problem(withPayload(ok, null))).isNotEqualTo(Optional.empty());
    assertThat(mapper.problem(withPayload(ok, new PartnerRequest.Payload(
            "PAY", "P2P", p.partner(), p.partnerTxnRef(), p.amount(), p.debitParty(), p.creditParty(), null,
            p.attributes()))))
        .contains("unsupported txnType");
    assertThat(mapper.problem(withPayload(ok, new PartnerRequest.Payload(
            "PAY", p.txnType(), p.partner(), p.partnerTxnRef(), p.amount(),
            new PartnerRequest.Party("MERCHANT", "M-1", null, null), p.creditParty(), null, p.attributes()))))
        .contains("debit party must be a customer wallet");
    assertThat(mapper.problem(withPayload(ok, new PartnerRequest.Payload(
            "PAY", p.txnType(), p.partner(), p.partnerTxnRef(), p.amount(), p.debitParty(),
            new PartnerRequest.Party("MERCHANT", "M-1", null, null), null, p.attributes()))))
        .contains("credit party must be a bank account or wallet");
    assertThat(mapper.problem(withPayload(ok, new PartnerRequest.Payload(
            "PAY", p.txnType(), p.partner(), p.partnerTxnRef(), p.amount(), p.debitParty(), p.creditParty(), null,
            null))))
        .contains("participantId is required");
    assertThat(mapper.problem(withPayload(ok, new PartnerRequest.Payload(
            "PAY", p.txnType(), p.partner(), p.partnerTxnRef(), p.amount(), p.debitParty(), p.creditParty(), null,
            Map.of("participantId", " ")))))
        .contains("participantId is required");
  }

  @Test
  void anOffUsBanglaQrPaymentPaysAMerchantWithoutAParticipant() {
    // ticket 122 builds on this: the off-us BanglaQR leg is a PAY of QR_PAY_INTEROP to a merchant
    PartnerRequest ok = pay(newTxnId(), ACCOUNT, 2000);
    PartnerRequest.Payload p = ok.payload();
    PartnerRequest qr = withPayload(ok, new PartnerRequest.Payload(
        "PAY", "QR_PAY_INTEROP", p.partner(), p.partnerTxnRef(), p.amount(), p.debitParty(),
        new PartnerRequest.Party("MERCHANT", "MID-0001", null, null), null, null));
    assertThat(mapper.problem(qr)).isEmpty();
  }

  @Test
  void aReturnNeedsTheOriginalReferenceAndAPositiveAmount() {
    PartnerRequest ok = pay(newTxnId(), ACCOUNT, 2000);
    PartnerRequest.Payload p = ok.payload();
    PartnerRequest reverse = withPayload(ok,
        new PartnerRequest.Payload("REVERSE", p.txnType(), p.partner(), null, p.amount(), null, null, "NPSB-1", null));
    assertThat(mapper.problem(reverse)).isEmpty();
    assertThat(mapper.toCall(reverse, "BITS-REF").operation()).isEqualTo("REVERSE");
    assertThat(mapper.toCall(reverse, "BITS-REF").input()).containsEntry("originalPartnerRef", "NPSB-1");
    PartnerRequest noAmount = withPayload(ok,
        new PartnerRequest.Payload("REVERSE", p.txnType(), p.partner(), null, null, null, null, "NPSB-1", null));
    assertThat(mapper.problem(noAmount)).contains("amount must be positive");
    PartnerRequest noOriginal = withPayload(ok,
        new PartnerRequest.Payload("REVERSE", p.txnType(), p.partner(), null, p.amount(), null, null, " ", null));
    assertThat(mapper.problem(noOriginal)).contains("originalPartnerRef is required");
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
