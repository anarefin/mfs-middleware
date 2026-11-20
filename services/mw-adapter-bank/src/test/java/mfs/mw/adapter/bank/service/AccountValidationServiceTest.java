package mfs.mw.adapter.bank.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import mfs.mw.adapter.bank.exception.MwException;
import mfs.mw.adapter.bank.partner.PartnerCall;
import mfs.mw.adapter.bank.partner.PartnerResponse;
import mfs.mw.adapter.bank.partner.route.PartnerGateway;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** AVL (ticket 117b): one INQUIRY on bank-<code>; the answer is the last 4 digits and the first initial. */
class AccountValidationServiceTest {

  private final PartnerGateway gateway = mock(PartnerGateway.class);
  private final AccountValidationService service = new AccountValidationService(gateway);

  @Test
  void aValidAccountIsItsLastFourAndTheHoldersInitial() {
    when(gateway.call(any())).thenReturn(new PartnerResponse.Success("VR-1", null, Map.of("holderName", "R.")));

    var view = service.validate("dbbl", "1234567890");

    assertThat(view.bankCode()).isEqualTo("dbbl");
    assertThat(view.accountLast4()).isEqualTo("7890");
    assertThat(view.holderName()).isEqualTo("R.");
    ArgumentCaptor<PartnerCall> sent = ArgumentCaptor.forClass(PartnerCall.class);
    verify(gateway).call(sent.capture());
    assertThat(sent.getValue().partnerId()).isEqualTo("bank-dbbl");
    assertThat(sent.getValue().operation()).isEqualTo("INQUIRY");
    assertThat(sent.getValue().input())
        .containsEntry("account", "1234567890")
        .containsEntry("creditParty", Map.of("type", "BANK_ACCOUNT", "id", "1234567890"));
  }

  @Test
  void aFullNameFromTheBankIsCutToItsInitial() {
    when(gateway.call(any())).thenReturn(new PartnerResponse.Success("VR-1", null, Map.of("holderName", "rahim uddin")));

    assertThat(service.validate("dbbl", "1234567890").holderName()).isEqualTo("R.");
  }

  @Test
  void aRefusalIsThePartnerCode() {
    when(gateway.call(any()))
        .thenReturn(new PartnerResponse.Rejected("4001", "no such account", "ACCOUNT_NOT_FOUND", false));

    assertThatThrownBy(() -> service.validate("dbbl", "1234567890"))
        .isInstanceOfSatisfying(MwException.class, e -> {
          assertThat(e.code()).isEqualTo("MW-PTR-BANK_DBBL-ACCOUNT_NOT_FOUND");
          assertThat(e.retryable()).isFalse();
        });
  }

  @Test
  void anUnknownBankIsNotSent() {
    when(gateway.call(any()))
        .thenReturn(new PartnerResponse.NotSent(PartnerResponse.NotSentReason.UNKNOWN_PARTNER, null));

    assertThatThrownBy(() -> service.validate("nobank", "1234567890"))
        .isInstanceOfSatisfying(MwException.class, e -> assertThat(e.code()).isEqualTo("MW-PTR-UNKNOWN"));
  }

  @Test
  void noHolderNameIsNoUsableAnswer() {
    when(gateway.call(any())).thenReturn(new PartnerResponse.Success("VR-1", null, Map.of()));

    assertThatThrownBy(() -> service.validate("dbbl", "1234567890"))
        .isInstanceOfSatisfying(MwException.class, e -> {
          assertThat(e.code()).isEqualTo("MW-TMO-PARTNER_MALFORMED_REPLY");
          assertThat(e.retryable()).isTrue();
        });
  }
}
