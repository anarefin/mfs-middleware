package mfs.mw.adapter.billpay.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import mfs.mw.adapter.billpay.exception.MwException;
import mfs.mw.adapter.billpay.mapper.PartnerInboundMapper;
import mfs.mw.adapter.billpay.messaging.EventPublisher;
import mfs.mw.adapter.billpay.messaging.Inbox;
import mfs.mw.adapter.billpay.partner.InboundCall;
import mfs.mw.adapter.billpay.partner.InboundResult;
import mfs.mw.adapter.billpay.partner.PartnerUnsupportedException;
import mfs.mw.adapter.billpay.partner.config.PartnerConfigStore;
import mfs.mw.adapter.billpay.partner.template.PartnerFrameworkSeeds;
import mfs.mw.adapter.billpay.partner.template.PartnerGateway;
import mfs.mw.adapter.billpay.partner.template.PartnerInstance;
import mfs.mw.adapter.billpay.repository.PartnerReferenceRepository;
import org.junit.jupiter.api.Test;

/** The failure answers of the callback service that the HTTP test cannot provoke. */
class PartnerCallbackServiceTest {

  static final String REF = "BITS01933B8E7C1A7B2E9F3D2A4C5E6F7A8C";

  final PartnerConfigStore store = mock(PartnerConfigStore.class);
  final PartnerGateway gateway = mock(PartnerGateway.class);
  final PartnerReferenceRepository references = mock(PartnerReferenceRepository.class);
  final Inbox inbox = mock(Inbox.class);
  final EventPublisher publisher = mock(EventPublisher.class);
  final PartnerCallbackService service =
      new PartnerCallbackService(
          store, gateway, references, new PartnerInboundMapper(), inbox, publisher,
          Clock.fixed(Instant.parse("2026-11-03T08:15:40Z"), ZoneOffset.UTC));
  final InboundCall call = new InboundCall("billagg", "POST", "/partner/billagg/callback/payments", Map.of(), new byte[0]);

  void knownPartnerAndReference() {
    PartnerInstance instance = mock(PartnerInstance.class);
    when(instance.config()).thenReturn(PartnerFrameworkSeeds.billagg());
    when(store.instance("billagg")).thenReturn(Optional.of(instance));
    when(references.findByReference(REF))
        .thenReturn(Optional.of(new PartnerReferenceRepository.Issued("01933b8e-7c1a-7b2e-9f3d-2a4c5e6f7a8c", "billagg")));
  }

  @Test
  void kafkaDownIsNotAcknowledgedSoThePartnerSendsAgain() {
    knownPartnerAndReference();
    when(gateway.inbound(call)).thenReturn(new InboundResult("AGG-1", REF, "SUCCESS", "000", null, Map.of()));
    when(inbox.processOnce(eq(PartnerCallbackService.CONSUMER), anyString(), any()))
        .thenThrow(new IllegalStateException("broker unreachable"));

    assertThatThrownBy(() -> service.receive(call))
        .isInstanceOfSatisfying(MwException.class, e -> {
          assertThat(e.code()).isEqualTo("MW-SYS-EVENT_NOT_WRITTEN");
          assertThat(e.status().value()).isEqualTo(503);
        });
  }

  @Test
  void thePublishedPartnerIsTheAuthenticatedRoutesNeverTheBodys() {
    knownPartnerAndReference();
    // the body names someone else everywhere it can; only the URL's partner signed it
    when(gateway.inbound(call))
        .thenReturn(new InboundResult("AGG-1", REF, "SUCCESS", "000", null, Map.of("partnerId", "ocs")));
    when(inbox.processOnce(eq(PartnerCallbackService.CONSUMER), anyString(), any()))
        .thenAnswer(inv -> {
          ((Runnable) inv.getArgument(2)).run();
          return true;
        });

    service.receive(call);

    org.mockito.ArgumentCaptor<mfs.mw.adapter.billpay.dto.PartnerInbound> sent =
        org.mockito.ArgumentCaptor.forClass(mfs.mw.adapter.billpay.dto.PartnerInbound.class);
    org.mockito.Mockito.verify(publisher)
        .publish(eq(PartnerCallbackService.TOPIC), eq("01933b8e-7c1a-7b2e-9f3d-2a4c5e6f7a8c"), any(), sent.capture());
    assertThat(sent.getValue().partnerId()).isEqualTo("billagg");
    assertThat(sent.getValue().family()).isEqualTo("billpay");
  }

  @Test
  void aPartnerWithoutCallbacksIsNotFound() {
    knownPartnerAndReference();
    when(gateway.inbound(call)).thenThrow(new PartnerUnsupportedException("rest-json-request-reply takes no callbacks"));

    assertThatThrownBy(() -> service.receive(call))
        .isInstanceOfSatisfying(MwException.class, e -> assertThat(e.code()).isEqualTo("MW-VAL-003"));
  }

  @Test
  void aReferenceIssuedForAnotherPartnerIsNotFound() {
    knownPartnerAndReference();
    when(references.findByReference(REF))
        .thenReturn(Optional.of(new PartnerReferenceRepository.Issued("01933b8e-7c1a-7b2e-9f3d-2a4c5e6f7a8c", "other")));
    when(gateway.inbound(call)).thenReturn(new InboundResult("AGG-1", REF, "SUCCESS", "000", null, Map.of()));

    assertThatThrownBy(() -> service.receive(call))
        .isInstanceOfSatisfying(MwException.class, e -> assertThat(e.code()).isEqualTo("MW-VAL-003"));
  }
}
