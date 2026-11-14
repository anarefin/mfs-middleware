package mfs.mw.app.gateway.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import mfs.mw.app.gateway.client.AuthClient;
import mfs.mw.app.gateway.config.AuthClientProperties;
import mfs.mw.app.gateway.dto.PinKey;
import mfs.mw.app.gateway.exception.MwException;
import org.junit.jupiter.api.Test;

/** The apps' PIN transport key: reused for the TTL, the last one kept while mw-auth is down. */
class PinKeyServiceTest {

  static final PinKey V1 = new PinKey("PIN_TRANSPORT.v1", "RSA-OAEP-SHA-256", "pem-1");
  static final PinKey V2 = new PinKey("PIN_TRANSPORT.v2", "RSA-OAEP-SHA-256", "pem-2");

  final AuthClient auth = mock(AuthClient.class);
  final MutableClock clock = new MutableClock(Instant.parse("2026-11-15T06:00:00Z"));
  final AuthClientProperties props =
      new AuthClientProperties(
          URI.create("https://mw-auth:8443"), URI.create("http://keycloak/token"), "mw-app-gateway", "",
          Duration.ofSeconds(2), Duration.ofSeconds(1), Duration.ofMinutes(5));
  final PinKeyService service = new PinKeyService(auth, props, clock);

  @Test
  void theKeyIsReusedForTheTtlThenAskedAgain() {
    when(auth.pinKey()).thenReturn(V1, V2);

    assertThat(service.current()).isEqualTo(V1);
    clock.advance(Duration.ofMinutes(4));
    assertThat(service.current()).isEqualTo(V1);
    clock.advance(Duration.ofMinutes(2));
    assertThat(service.current()).isEqualTo(V2);
    verify(auth, times(2)).pinKey();
  }

  @Test
  void theLastKeyIsServedWhileMwAuthIsDownAndNoneIsAnOutage() {
    MwException down = new MwException("MW-SYS-012", "down");
    when(auth.pinKey()).thenThrow(down);
    assertThatThrownBy(service::current).isSameAs(down);

    AuthClient flaky = mock(AuthClient.class);
    when(flaky.pinKey()).thenReturn(V1).thenThrow(down);
    PinKeyService warm = new PinKeyService(flaky, props, clock);
    assertThat(warm.current()).isEqualTo(V1);
    clock.advance(Duration.ofMinutes(6));
    assertThat(warm.current()).isEqualTo(V1);
  }

  static final class MutableClock extends java.time.Clock {
    private Instant now;

    MutableClock(Instant now) {
      this.now = now;
    }

    void advance(Duration d) {
      now = now.plus(d);
    }

    @Override
    public java.time.ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public java.time.Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
