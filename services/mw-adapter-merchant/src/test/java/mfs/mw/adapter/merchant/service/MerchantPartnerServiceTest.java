package mfs.mw.adapter.merchant.service;

import static mfs.mw.adapter.merchant.MerchantFixtures.NOW;
import static mfs.mw.adapter.merchant.MerchantFixtures.newTxnId;
import static mfs.mw.adapter.merchant.MerchantFixtures.pay;
import static mfs.mw.adapter.merchant.MerchantFixtures.statusQuery;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Optional;
import mfs.mw.adapter.merchant.dto.PartnerReply;
import mfs.mw.adapter.merchant.dto.PartnerRequest;
import mfs.mw.adapter.merchant.mapper.PartnerCallMapper;
import mfs.mw.adapter.merchant.mapper.PartnerReplyMapper;
import mfs.mw.adapter.merchant.messaging.Inbox;
import mfs.mw.adapter.merchant.messaging.OutboxWriter;
import mfs.mw.adapter.merchant.partner.route.PartnerGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * M9 review: a NOTIFY (PAY) whose merchant call throws after the claim still gets a reply, UNKNOWN,
 * so the orchestrator retries it with backoff and finally dead-letters it; nothing is lost silently.
 */
class MerchantPartnerServiceTest {

  final Clock clock = Clock.fixed(NOW.minusSeconds(1), ZoneOffset.UTC);
  final PartnerCallMapper requests = mock(PartnerCallMapper.class);
  final PartnerReferenceService references = mock(PartnerReferenceService.class);
  final PartnerGateway partners = mock(PartnerGateway.class);
  final Inbox inbox = mock(Inbox.class);
  final OutboxWriter outbox = mock(OutboxWriter.class);
  final MerchantPartnerService service = new MerchantPartnerService(
      requests, new PartnerReplyMapper(clock), references, partners, inbox, outbox, clock, new SimpleMeterRegistry());

  @BeforeEach
  void inboxRunsTheSideEffect() {
    when(inbox.processOnce(anyString(), anyString(), any())).thenAnswer(a -> {
      a.getArgument(2, Runnable.class).run();
      return true;
    });
    when(requests.problem(any())).thenReturn(Optional.empty());
    when(references.referenceFor(anyString(), anyString())).thenReturn("BITSREF1");
  }

  @Test
  void aNotifyWhoseCallFailsIsAnsweredUnknown() {
    PartnerRequest request = pay(newTxnId(), "01911000001", 2000);
    when(partners.call(any())).thenThrow(new IllegalStateException("route blew up"));

    service.process(request);

    ArgumentCaptor<Object> reply = ArgumentCaptor.forClass(Object.class);
    verify(outbox).append(eq("mw.partner.reply.v1"), eq(request.txnId()), any(), reply.capture());
    PartnerReply r = (PartnerReply) reply.getValue();
    assertThat(r.requestId()).isEqualTo(request.requestId());
    assertThat(r.payload().operation()).isEqualTo("PAY");
    assertThat(r.payload().outcome()).isEqualTo("UNKNOWN");
    assertThat(r.payload().errorCode()).isEqualTo("MW-TMO-PARTNER_CALL_FAILED");
  }

  @Test
  void anotherOperationWhoseCallFailsIsLeftToTheOrchestratorsDeadline() {
    PartnerRequest request = statusQuery(newTxnId());
    when(partners.call(any())).thenThrow(new IllegalStateException("route blew up"));

    assertThatThrownBy(() -> service.process(request)).isInstanceOf(IllegalStateException.class);
    verify(outbox, never()).append(anyString(), anyString(), any(), any());
  }
}
