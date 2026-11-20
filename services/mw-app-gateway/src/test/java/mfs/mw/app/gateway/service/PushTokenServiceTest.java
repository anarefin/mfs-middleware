package mfs.mw.app.gateway.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import mfs.mw.app.gateway.client.AuthClient;
import mfs.mw.app.gateway.dto.AppUser;
import mfs.mw.app.gateway.dto.LiveSession;
import mfs.mw.app.gateway.dto.PushTokenMessage;
import mfs.mw.app.gateway.dto.PushTokenRequest;
import mfs.mw.app.gateway.exception.MwException;
import mfs.mw.app.gateway.messaging.EventPublisher;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Ticket 110b: a push token registration names mw-auth's binding epoch of the device's current binding (from a
 * fresh live session lookup), so mw-notify orders it against re-binds without comparing clocks.
 */
class PushTokenServiceTest {

  static final Instant NOW = Instant.parse("2026-11-20T08:00:00Z");
  static final AppUser USER = new AppUser("01711000001", "CUSTOMER_APP", "CUSTOMER");
  static final PushTokenRequest BODY = new PushTokenRequest("fcm-test-token-abc123", "ANDROID");

  final EventPublisher publisher = mock(EventPublisher.class);
  final AuthClient auth = mock(AuthClient.class);
  final PushTokenService service = new PushTokenService(publisher, auth, Clock.fixed(NOW, ZoneOffset.UTC));

  static LiveSession session(Long epoch) {
    return new LiveSession("01711000001", "dev-0001", 300, "ab".repeat(32), NOW.minusSeconds(60), epoch);
  }

  @Test
  void theRegistrationCarriesTheBindingEpochOfTheDevicesLiveSession() {
    when(auth.session("01711000001", "dev-0001")).thenReturn(Optional.of(session(42L)));

    service.register(USER, "dev-0001", BODY);

    ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
    verify(publisher).publish(eq(PushTokenService.TOPIC), eq("01711000001"), any(), payload.capture());
    PushTokenMessage msg = (PushTokenMessage) payload.getValue();
    assertThat(msg.bindingEpoch()).isEqualTo(42L);
    assertThat(msg.deviceId()).isEqualTo("dev-0001");
    assertThat(msg.registeredAt()).isEqualTo(NOW);
  }

  @Test
  void withoutALiveSessionNothingIsPublished() {
    when(auth.session("01711000001", "dev-0001")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.register(USER, "dev-0001", BODY))
        .isInstanceOfSatisfying(MwException.class, e -> assertThat(e.code()).isEqualTo("MW-AUTH-007"));
    verifyNoInteractions(publisher);
  }

  @Test
  void aSessionWithoutABindingEpochIsAnOutageAndNothingIsPublished() {
    when(auth.session("01711000001", "dev-0001")).thenReturn(Optional.of(session(null)));

    assertThatThrownBy(() -> service.register(USER, "dev-0001", BODY))
        .isInstanceOfSatisfying(MwException.class, e -> assertThat(e.code()).isEqualTo("MW-SYS-012"));
    verifyNoInteractions(publisher);
  }
}
