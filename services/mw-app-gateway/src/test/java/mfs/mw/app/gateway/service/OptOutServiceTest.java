package mfs.mw.app.gateway.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import mfs.mw.app.gateway.dto.AppUser;
import mfs.mw.app.gateway.dto.OptOutMessage;
import mfs.mw.app.gateway.messaging.EventPublisher;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** The app opt-out names the token's MSISDN and the APP channel (ticket 111, NOT-09). */
class OptOutServiceTest {

  @Test
  void theTokenMsisdnIsPublishedAsAnAppOptOut() {
    EventPublisher publisher = mock(EventPublisher.class);
    Instant now = Instant.parse("2026-11-20T08:00:00Z");
    new OptOutService(publisher, Clock.fixed(now, ZoneOffset.UTC))
        .optOut(new AppUser("01711000001", "APP", "CUSTOMER"), "dev-0001");

    ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
    verify(publisher).publish(org.mockito.ArgumentMatchers.eq(OptOutService.TOPIC),
        org.mockito.ArgumentMatchers.eq("01711000001"), org.mockito.ArgumentMatchers.any(), payload.capture());
    OptOutMessage msg = (OptOutMessage) payload.getValue();
    assertThat(msg.walletId()).isEqualTo("01711000001");
    assertThat(msg.channel()).isEqualTo("APP");
    assertThat(msg.optedOutAt()).isEqualTo(now);
    assertThat(msg.schemaVersion()).isEqualTo(1);
  }
}
