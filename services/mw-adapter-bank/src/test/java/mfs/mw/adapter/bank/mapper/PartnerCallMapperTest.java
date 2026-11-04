package mfs.mw.adapter.bank.mapper;

import static mfs.mw.adapter.bank.BankFixtures.ACCOUNT;
import static mfs.mw.adapter.bank.BankFixtures.newTxnId;
import static mfs.mw.adapter.bank.BankFixtures.pay;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Optional;
import mfs.mw.adapter.bank.BankFixtures;
import mfs.mw.adapter.bank.dto.PartnerRequest;
import mfs.mw.adapter.bank.partner.PartnerCall;
import org.junit.jupiter.api.Test;

/** The canonical call the partner mappings read (amounts in minor units and as a major-unit string). */
class PartnerCallMapperTest {

  final PartnerCallMapper mapper = new PartnerCallMapper();

  @Test
  void aPayBecomesACanonicalDebitCallWithOurReferenceTheAmountTheAccountAndTheWallet() {
    PartnerRequest request = pay(newTxnId(), ACCOUNT, 2000);

    PartnerCall call = mapper.toCall(request, "UPAYREF1");

    assertThat(call.partnerId()).isEqualTo("c2wbank");
    assertThat(call.operation()).isEqualTo("PAY");
    assertThat(call.txnType()).isEqualTo("ADD_MONEY_BANK");
    assertThat(call.txnId()).isEqualTo(request.txnId());
    assertThat(call.correlationId()).isEqualTo("corr-0002");
    assertThat(call.reference()).isEqualTo("UPAYREF1");
    assertThat(call.input())
        .containsEntry("amount", Map.of("valueMinor", 2000L, "currency", "BDT", "major", "20.00"))
        .containsEntry("debitParty", Map.of("type", "BANK_ACCOUNT", "id", ACCOUNT))
        .containsEntry("creditParty", Map.of("type", "WALLET", "id", "01711000001", "walletType", "CUSTOMER"))
        .containsEntry("partner", Map.of("partnerId", "c2wbank", "productCode", "C2W"));
  }

  @Test
  void theMajorUnitAmountKeepsTheFractionalPart() {
    assertThat(major(12305)).isEqualTo("123.05");
    assertThat(major(5)).isEqualTo("0.05");
  }

  @Test
  void aStatusQueryCarriesOnlyWhatItHas() {
    PartnerCall call = mapper.toCall(BankFixtures.statusQuery(newTxnId()), "R");
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
    PartnerRequest q = BankFixtures.statusQuery(newTxnId());
    assertThat(mapper.problem(q)).isEmpty();
    PartnerRequest.Payload p = q.payload();
    PartnerRequest bare =
        withPayload(q, new PartnerRequest.Payload("STATUS_QUERY", "ADD_MONEY_BANK", p.partner(), "x", null, null, null, null, null));
    assertThat(mapper.problem(bare)).isEmpty();
    PartnerRequest noPartner =
        withPayload(q, new PartnerRequest.Payload("STATUS_QUERY", "ADD_MONEY_BANK", null, "x", null, null, null, null, null));
    assertThat(mapper.problem(noPartner)).contains("partner is required");
  }

  @Test
  void reportsWhatIsWrongWithARequestThatCannotBeSent() {
    PartnerRequest ok = pay(newTxnId(), ACCOUNT, 2000);
    PartnerRequest.Payload p = ok.payload();

    assertThat(mapper.problem(withOperation(ok, "REVERSE"))).contains("unsupported operation");
    assertThat(mapper.problem(withFamily(ok, "billpay"))).contains("unsupported family");
    assertThat(mapper.problem(pay(newTxnId(), ACCOUNT, 0))).contains("amount must be positive");
    assertThat(mapper.problem(pay(newTxnId(), "12-34", 100))).contains("debit party must be a bank account");
    assertThat(mapper.problem(withPayload(ok, null))).isNotEqualTo(Optional.empty());
    assertThat(mapper.problem(withPayload(ok, new PartnerRequest.Payload(
            "PAY", "W2B", p.partner(), p.partnerTxnRef(), p.amount(), p.debitParty(), p.creditParty(), null, null))))
        .contains("unsupported txnType");
    assertThat(mapper.problem(withPayload(ok, new PartnerRequest.Payload(
            "PAY", p.txnType(), p.partner(), p.partnerTxnRef(), p.amount(), p.debitParty(),
            new PartnerRequest.Party("MSISDN", "01711000001", null, null), null, null))))
        .contains("credit party must be a wallet");
    assertThat(mapper.problem(withPayload(ok, new PartnerRequest.Payload(
            "PAY", p.txnType(), p.partner(), p.partnerTxnRef(), p.amount(),
            new PartnerRequest.Party("WALLET", "01711000002", null, null), p.creditParty(), null, null))))
        .contains("debit party must be a bank account");
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
