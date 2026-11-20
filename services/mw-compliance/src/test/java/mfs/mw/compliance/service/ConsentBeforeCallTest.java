package mfs.mw.compliance.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import mfs.mw.compliance.client.CoreWalletClient;
import mfs.mw.compliance.client.EcNidClient;
import mfs.mw.compliance.config.ComplianceProperties;
import mfs.mw.compliance.dto.Caller;
import mfs.mw.compliance.dto.Channel;
import mfs.mw.compliance.dto.ConsentRequest;
import mfs.mw.compliance.dto.CreateApplicationRequest;
import mfs.mw.compliance.dto.DocumentType;
import mfs.mw.compliance.dto.KycStatus;
import mfs.mw.compliance.entity.KycApplication;
import mfs.mw.compliance.exception.ErrorCodes;
import mfs.mw.compliance.exception.MwException;
import mfs.mw.compliance.messaging.OutboxWriter;
import mfs.mw.compliance.repository.KycStore;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** CMP-04: no EC request without a committed consent row; the consent commits before the application. */
class ConsentBeforeCallTest {

  private final KycStore store = mock(KycStore.class);
  private final PiiCipher pii = mock(PiiCipher.class);
  private final EcNidClient ec = mock(EcNidClient.class);
  private final OutboxWriter outbox = mock(OutboxWriter.class);
  private final KycApplicationService service = new KycApplicationService(
      store, pii, ec, mock(CoreWalletClient.class), new KycMatchService(ComplianceProperties.defaults()), outbox,
      new TransactionTemplate(mock(PlatformTransactionManager.class)),
      Clock.fixed(Instant.parse("2026-11-20T10:00:00Z"), ZoneOffset.UTC), ComplianceProperties.defaults());

  private static KycApplication submitted(UUID id, UUID consentId) {
    Instant now = Instant.parse("2026-11-20T09:00:00Z");
    return new KycApplication(id, "01712345678", Channel.APP, null, consentId, KycStatus.SUBMITTED, "NID_TOKEN",
        "789", "{}", "{}", "{}", null, null, null, null, null, "corr-1", now, now);
  }

  @Test
  void submitWithoutCommittedConsentNeverCallsTheEc() {
    UUID id = UUID.randomUUID();
    UUID consentId = UUID.randomUUID();
    when(store.lockApplication(id)).thenReturn(Optional.of(submitted(id, consentId)));
    when(store.documentTypes(id)).thenReturn(List.of(DocumentType.NID_FRONT, DocumentType.NID_BACK, DocumentType.SELFIE));
    when(store.consentExists(consentId)).thenReturn(false);

    MwException e = catchThrowableOfType(MwException.class, () -> service.submit(id, Caller.wallet("mw-app-gateway", "01712345678")));

    assertThat(e.code()).isEqualTo(ErrorCodes.VAL_CONSENT_MISSING);
    verifyNoInteractions(ec);
  }

  @Test
  void createWritesTheConsentBeforeTheApplication() {
    when(pii.tokeniseNid(anyString())).thenReturn("NID_TOKEN");
    when(pii.seal(anyString(), anyString())).thenReturn("{}");
    CreateApplicationRequest req = new CreateApplicationRequest("01712345678", Channel.DSO, "AGT-7",
        "1990123456789", LocalDate.of(1990, 5, 17), "Rahim Uddin", null, new ConsentRequest("EKYC_ONBOARDING", null));

    service.create(req, Caller.agent("mw-app-gateway", "AGT-7"), "corr-1");

    InOrder order = inOrder(store);
    order.verify(store).insertConsent(any());
    order.verify(store).insertApplication(any());
    verifyNoInteractions(ec);
  }
}
