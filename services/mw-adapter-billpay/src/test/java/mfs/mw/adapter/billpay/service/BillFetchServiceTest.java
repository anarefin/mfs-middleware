package mfs.mw.adapter.billpay.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Map;
import mfs.mw.adapter.billpay.config.BillerProperties;
import mfs.mw.adapter.billpay.dto.BillView;
import mfs.mw.adapter.billpay.dto.BillerView;
import mfs.mw.adapter.billpay.exception.MwException;
import mfs.mw.adapter.billpay.mapper.PartnerCallMapper;
import mfs.mw.adapter.billpay.partner.PartnerCall;
import mfs.mw.adapter.billpay.partner.PartnerResponse;
import mfs.mw.adapter.billpay.partner.PartnerResponse.NotSentReason;
import mfs.mw.adapter.billpay.partner.PartnerResponse.UnknownReason;
import mfs.mw.adapter.billpay.partner.template.PartnerGateway;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Biller catalogue and bill fetch of the internal API (ticket 21): every framework outcome. */
class BillFetchServiceTest {

  final PartnerGateway gateway = mock(PartnerGateway.class);
  final BillFetchService service =
      new BillFetchService(
          new BillerProperties(
              Map.of(
                  "DESCO", new BillerProperties.Biller("Dhaka Electric", "ELECTRICITY", "billagg"),
                  "WASA", new BillerProperties.Biller("Dhaka WASA", "WATER", "billagg"))),
          new PartnerCallMapper(),
          gateway);

  MwException refusal(PartnerResponse response) {
    when(gateway.call(any())).thenReturn(response);
    try {
      service.fetch("DESCO", "1234567");
    } catch (MwException e) {
      return e;
    }
    throw new AssertionError("no refusal for " + response);
  }

  @Test
  void theCatalogueIsSortedByCodeAndNamesTheAggregator() {
    assertThat(service.billers())
        .containsExactly(
            new BillerView("DESCO", "Dhaka Electric", "ELECTRICITY", "billagg"),
            new BillerView("WASA", "Dhaka WASA", "WATER", "billagg"));
  }

  @Test
  void aFetchAsksTheBillersAggregatorAndReturnsTheAmountDueInPoisha() {
    when(gateway.call(any()))
        .thenReturn(
            new PartnerResponse.Success(
                "1234567", "000", Map.of("amount", "667.50", "dueDate", "2026-11-14", "billPeriod", "2026-11")));

    BillView bill = service.fetch("DESCO", "1234567");

    assertThat(bill.amount().valueMinor()).isEqualTo(66_750L);
    assertThat(bill.amount().currency()).isEqualTo("BDT");
    assertThat(bill.dueDate()).isEqualTo("2026-11-14");
    assertThat(bill.billPeriod()).isEqualTo("2026-11");
    assertThat(bill.billNumber()).isEqualTo("1234567");
    ArgumentCaptor<PartnerCall> call = ArgumentCaptor.forClass(PartnerCall.class);
    org.mockito.Mockito.verify(gateway).call(call.capture());
    assertThat(call.getValue().partnerId()).isEqualTo("billagg");
    assertThat(call.getValue().operation()).isEqualTo("INQUIRY");
  }

  @Test
  void anUnknownBillerIs404WithoutAskingAnyone() {
    assertThatThrownBy(() -> service.fetch("NOPE", "1"))
        .isInstanceOfSatisfying(
            MwException.class,
            e -> {
              assertThat(e.code()).isEqualTo("MW-VAL-003");
              assertThat(e.status().value()).isEqualTo(404);
            });
    verifyNoInteractions(gateway);
  }

  @Test
  void aRefusalIsTheMappedPartnerCode() {
    MwException e = refusal(new PartnerResponse.Rejected("3001", "Bill not found", "BILL_NOT_FOUND", false));
    assertThat(e.code()).isEqualTo("MW-PTR-BILLAGG-BILL_NOT_FOUND");
    assertThat(e.status().value()).isEqualTo(502);
    assertThat(e.retryable()).isFalse();
  }

  @Test
  void noAnswerIsATimeoutAndNothingSentIsUnavailableBothRetryable() {
    MwException timeout = refusal(new PartnerResponse.Unknown(UnknownReason.TIMEOUT));
    assertThat(timeout.code()).isEqualTo("MW-TMO-PARTNER_TIMEOUT");
    assertThat(timeout.status().value()).isEqualTo(504);
    assertThat(timeout.retryable()).isTrue();

    MwException ambiguous = refusal(new PartnerResponse.Ambiguous("9876", "?"));
    assertThat(ambiguous.code()).isEqualTo("MW-TMO-PARTNER_AMBIGUOUS_CODE");

    MwException open = refusal(new PartnerResponse.NotSent(NotSentReason.CIRCUIT_OPEN));
    assertThat(open.code()).isEqualTo("MW-SYS-PARTNER_CIRCUIT_OPEN");
    assertThat(open.status().value()).isEqualTo(503);
    assertThat(open.retryable()).isTrue();
  }

  @Test
  void aSuccessWithoutAReadableAmountIsAMalformedReply() {
    MwException e = refusal(new PartnerResponse.Success("1234567", "000", Map.of("amount", "lots")));
    assertThat(e.code()).isEqualTo("MW-TMO-PARTNER_MALFORMED_REPLY");
    MwException neg = refusal(new PartnerResponse.Success("1234567", "000", Map.of("amount", "-1.00")));
    assertThat(neg.code()).isEqualTo("MW-TMO-PARTNER_MALFORMED_REPLY");
  }

  @Test
  void aPendingOrNotFoundAnswerIsNotABill() {
    assertThat(refusal(new PartnerResponse.Pending("x", null)).code()).isEqualTo("MW-TMO-PARTNER_MALFORMED_REPLY");
    assertThat(refusal(new PartnerResponse.NotFound("x", null)).code()).isEqualTo("MW-TMO-PARTNER_MALFORMED_REPLY");
  }
}
