package mfs.mw.ussd.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import mfs.mw.ussd.client.AuthClient;
import mfs.mw.ussd.config.AuthClientProperties;
import mfs.mw.ussd.dto.PinKey;
import mfs.mw.ussd.util.TestPinKeys;

/** A {@link PinTransport} on the per-run test key ({@link TestPinKeys}), mw-auth mocked. */
final class TestPinTransport {

  static final String KEY_ID = "PIN_TRANSPORT.v1";

  private TestPinTransport() {}

  static AuthClientProperties props() {
    URI u = URI.create("http://localhost:1");
    return new AuthClientProperties(
        u, u, "mw-ussd", "", Duration.ofSeconds(2), Duration.ofSeconds(1), Duration.ofMinutes(10));
  }

  static AuthClient auth() {
    AuthClient auth = mock(AuthClient.class);
    when(auth.pinKey()).thenReturn(new PinKey(KEY_ID, "RSA-OAEP-SHA-256", TestPinKeys.pem()));
    return auth;
  }

  static PinTransport create() {
    return new PinTransport(auth(), props(), Clock.systemUTC());
  }
}
