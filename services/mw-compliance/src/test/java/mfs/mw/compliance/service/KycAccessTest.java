package mfs.mw.compliance.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
import mfs.mw.compliance.dto.DocumentRequest;
import mfs.mw.compliance.dto.DocumentType;
import mfs.mw.compliance.dto.KycStatus;
import mfs.mw.compliance.entity.KycApplication;
import mfs.mw.compliance.exception.ErrorCodes;
import mfs.mw.compliance.exception.MwException;
import mfs.mw.compliance.messaging.OutboxWriter;
import mfs.mw.compliance.repository.KycStore;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Security review (ticket 124a): an application is only visible to the wallet that applied or the agent who
 * assisted (404 otherwise), only mw-admin-api looks up unscoped; and an NID another wallet already holds (matched,
 * approved or being checked) goes to manual review without an EC call or a level.
 */
class KycAccessTest {

  static final String WALLET = "01712345678";
  static final String AGENT = "DSO-17";

  private final KycStore store = mock(KycStore.class);
  private final PiiCipher pii = mock(PiiCipher.class);
  private final EcNidClient ec = mock(EcNidClient.class);
  private final OutboxWriter outbox = mock(OutboxWriter.class);
  private final KycApplicationService service = new KycApplicationService(
      store, pii, ec, mock(CoreWalletClient.class), new KycMatchService(ComplianceProperties.defaults()), outbox,
      new TransactionTemplate(mock(PlatformTransactionManager.class)),
      Clock.fixed(Instant.parse("2026-11-20T10:00:00Z"), ZoneOffset.UTC), ComplianceProperties.defaults());

  private static KycApplication app(UUID id, Channel channel, String agentId) {
    Instant now = Instant.parse("2026-11-20T09:00:00Z");
    return new KycApplication(id, WALLET, channel, agentId, UUID.randomUUID(), KycStatus.SUBMITTED, "NID_TOKEN",
        "789", "{}", "{}", "{}", null, null, null, null, null, "corr-1", now, now);
  }

  private static void assertNotFound(Runnable call) {
    MwException e = catchThrowableOfType(MwException.class, call::run);
    assertThat(e).isNotNull();
    assertThat(e.code()).isEqualTo(ErrorCodes.VAL_NOT_FOUND);
    assertThat(e.status()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void anotherWalletGets404OnEveryOperation() {
    UUID id = UUID.randomUUID();
    when(store.findApplication(id)).thenReturn(Optional.of(app(id, Channel.APP, null)));
    when(store.lockApplication(id)).thenReturn(Optional.of(app(id, Channel.APP, null)));
    Caller other = Caller.wallet("mw-app-gateway", "01812345678");

    assertNotFound(() -> service.get(id, other));
    assertNotFound(() -> service.addDocument(id, new DocumentRequest(DocumentType.SELFIE, "image/jpeg", "/9j/4A=="), other));
    assertNotFound(() -> service.submit(id, other));
    verifyNoInteractions(ec, pii);
    verify(store, never()).upsertDocument(any());
  }

  @Test
  void anotherAgentGets404AndTheAssistingAgentSeesIt() {
    UUID id = UUID.randomUUID();
    when(store.findApplication(id)).thenReturn(Optional.of(app(id, Channel.DSO, AGENT)));
    when(store.documentTypes(id)).thenReturn(List.of());

    assertNotFound(() -> service.get(id, Caller.agent("mw-app-gateway", "DSO-99")));
    // A wallet-scoped caller does not see an assisted application of another wallet either.
    assertThat(service.get(id, Caller.agent("mw-app-gateway", AGENT)).applicationId()).isEqualTo(id);
    assertThat(service.get(id, Caller.wallet("mw-app-gateway", WALLET)).applicationId()).isEqualTo(id);
  }

  @Test
  void onlyTheUnscopedAdminCallerSeesAnyApplication() {
    UUID id = UUID.randomUUID();
    when(store.findApplication(id)).thenReturn(Optional.of(app(id, Channel.APP, null)));
    when(store.documentTypes(id)).thenReturn(List.of());

    assertThat(service.get(id, Caller.unscoped("mw-admin-api")).applicationId()).isEqualTo(id);
  }

  @Test
  void theUnscopedCallerMayOnlyRead() {
    UUID id = UUID.randomUUID();
    when(store.findApplication(id)).thenReturn(Optional.of(app(id, Channel.APP, null)));
    when(store.lockApplication(id)).thenReturn(Optional.of(app(id, Channel.APP, null)));
    Caller console = Caller.unscoped("mw-admin-api");
    CreateApplicationRequest assisted = new CreateApplicationRequest(WALLET, Channel.DSO, "DSO-99", "1990123456789",
        LocalDate.of(1990, 5, 17), "Rahim Uddin", null, new ConsentRequest("EKYC_ONBOARDING", null));

    for (Runnable write : List.<Runnable>of(
        () -> service.create(assisted, console, "corr-1"),
        () -> service.addDocument(id, new DocumentRequest(DocumentType.SELFIE, "image/jpeg", "/9j/4A=="), console),
        () -> service.submit(id, console))) {
      MwException e = catchThrowableOfType(MwException.class, write::run);
      assertThat(e).isNotNull();
      assertThat(e.code()).isEqualTo(ErrorCodes.AUTH_FORBIDDEN);
    }
    verify(store, never()).insertApplication(any());
    verify(store, never()).upsertDocument(any());
    verifyNoInteractions(ec, pii);
  }

  @Test
  void aWalletMayOnlyApplyForItselfAndAnAgentAlwaysAppliesAsItself() {
    when(pii.tokeniseNid(anyString())).thenReturn("NID_TOKEN");
    when(pii.seal(anyString(), anyString())).thenReturn("{}");
    CreateApplicationRequest self = new CreateApplicationRequest(WALLET, Channel.APP, null, "1990123456789",
        LocalDate.of(1990, 5, 17), "Rahim Uddin", null, new ConsentRequest("EKYC_ONBOARDING", null));

    MwException e = catchThrowableOfType(MwException.class,
        () -> service.create(self, Caller.wallet("mw-app-gateway", "01812345678"), "corr-1"));
    assertThat(e.code()).isEqualTo(ErrorCodes.AUTH_FORBIDDEN);
    verify(store, never()).insertApplication(any());

    CreateApplicationRequest assisted = new CreateApplicationRequest(WALLET, Channel.DSO, "DSO-99", "1990123456789",
        LocalDate.of(1990, 5, 17), "Rahim Uddin", null, new ConsentRequest("EKYC_ONBOARDING", null));
    MwException spoofed = catchThrowableOfType(MwException.class,
        () -> service.create(assisted, Caller.agent("mw-app-gateway", AGENT), "corr-1"));
    assertThat(spoofed.code()).isEqualTo(ErrorCodes.AUTH_FORBIDDEN);
  }

  @Test
  void anNidAnotherWalletHoldsGoesToManualReviewWithoutAnEcCall() {
    UUID id = UUID.randomUUID();
    KycApplication a = app(id, Channel.APP, null);
    when(store.lockApplication(id)).thenReturn(Optional.of(a));
    when(store.findApplication(id)).thenReturn(Optional.of(a));
    when(store.documentTypes(id)).thenReturn(List.of(DocumentType.NID_FRONT, DocumentType.NID_BACK, DocumentType.SELFIE));
    when(store.consentExists(a.consentId())).thenReturn(true);
    when(store.nidHeldByAnotherWallet("NID_TOKEN", WALLET)).thenReturn(true);

    service.submit(id, Caller.wallet("mw-app-gateway", WALLET));

    verify(store).recordOutcome(eq(id), eq(KycStatus.MANUAL_REVIEW), eq(null), eq(0.0), eq(null),
        eq(KycApplicationService.NID_IN_USE), any());
    verify(store, never()).updateStatus(eq(id), eq(KycStatus.EC_CALLED), any(), any());
    verifyNoInteractions(ec, outbox);
  }
}
