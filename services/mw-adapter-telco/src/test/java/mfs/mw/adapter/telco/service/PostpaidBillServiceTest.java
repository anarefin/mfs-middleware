package mfs.mw.adapter.telco.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;
import mfs.mw.adapter.telco.exception.MwException;
import mfs.mw.adapter.telco.partner.PartnerCall;
import mfs.mw.adapter.telco.partner.PartnerResponse;
import mfs.mw.adapter.telco.partner.route.PartnerGateway;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Postpaid INQUIRY (ticket 112b): major-unit OCS strings become poisha. The bill reference is not kept. */
class PostpaidBillServiceTest {

  private final PartnerGateway gateway = mock(PartnerGateway.class);
  private final PostpaidBillService service = new PostpaidBillService(gateway);

  @Test
  void grameenphoneOutstandingAndMinimumBecomePoisha() {
    when(gateway.call(org.mockito.ArgumentMatchers.any())).thenReturn(success("450.00", "50.00"));

    var bill = service.fetch("gp-ocs", "01711000021");

    assertThat(bill.outstanding().valueMinor()).isEqualTo(45_000);
    assertThat(bill.outstanding().currency()).isEqualTo("BDT");
    assertThat(bill.minimum().valueMinor()).isEqualTo(5_000);
    ArgumentCaptor<PartnerCall> sent = ArgumentCaptor.forClass(PartnerCall.class);
    org.mockito.Mockito.verify(gateway).call(sent.capture());
    assertThat(sent.getValue().partnerId()).isEqualTo("gp-ocs");
    assertThat(sent.getValue().operation()).isEqualTo("INQUIRY");
    assertThat(sent.getValue().input().get("creditParty")).isEqualTo(Map.of("type", "MSISDN", "id", "01711000021"));
  }

  @Test
  void aFractionalBillKeepsThePoisha() {
    when(gateway.call(org.mockito.ArgumentMatchers.any())).thenReturn(success("320.50", "20.00"));

    var bill = service.fetch("robi-ocs", "01811000021");

    assertThat(bill.outstanding().valueMinor()).isEqualTo(32_050);
    assertThat(bill.minimum().valueMinor()).isEqualTo(2_000);
  }

  @Test
  void aThirdDecimalIsNotABill() {
    when(gateway.call(org.mockito.ArgumentMatchers.any())).thenReturn(success("450.001", "50.00"));

    assertThatThrownBy(() -> service.fetch("gp-ocs", "01711000021"))
        .isInstanceOfSatisfying(MwException.class, e -> assertThat(e.code()).isEqualTo("MW-TMO-PARTNER_MALFORMED_REPLY"));
  }

  @Test
  void aDefiniteRefusalNamesTheProfile() {
    when(gateway.call(org.mockito.ArgumentMatchers.any()))
        .thenReturn(new PartnerResponse.Rejected("17", "No such subscriber", "NO_SUBSCRIBER", false));

    assertThatThrownBy(() -> service.fetch("gp-ocs", "01711000021"))
        .isInstanceOfSatisfying(MwException.class, e -> {
          assertThat(e.code()).isEqualTo("MW-PTR-GP_OCS-NO_SUBSCRIBER");
          assertThat(e.status().value()).isEqualTo(502);
        });
  }

  private static PartnerResponse.Success success(String outstanding, String minimum) {
    return new PartnerResponse.Success(null, "00", Map.of("outstanding", outstanding, "minimum", minimum));
  }
}
