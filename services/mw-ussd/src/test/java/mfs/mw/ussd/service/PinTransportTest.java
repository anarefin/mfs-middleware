package mfs.mw.ussd.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import mfs.mw.ussd.client.AuthClient;
import mfs.mw.ussd.dto.EncryptedPin;
import mfs.mw.ussd.dto.PinKey;
import mfs.mw.ussd.exception.ErrorCodes;
import mfs.mw.ussd.exception.MwException;
import mfs.mw.ussd.util.TestPinKeys;
import org.junit.jupiter.api.Test;

/** The PIN transport key from mw-auth (ticket 72): cached for its TTL, dropped on demand. */
class PinTransportTest {

  /** A clock the test moves. */
  static final class Moving extends Clock {
    Instant now = Instant.parse("2026-11-15T10:00:00Z");

    @Override
    public ZoneId getZone() {
      return ZoneId.of("UTC");
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  final AuthClient auth = mock(AuthClient.class);
  final Moving clock = new Moving();
  final PinTransport pins = new PinTransport(auth, TestPinTransport.props(), clock);

  @Test
  void encryptsUnderTheTransportKeyWithItsKid() throws Exception {
    when(auth.pinKey()).thenReturn(new PinKey("PIN_TRANSPORT.v3", "RSA-OAEP-SHA-256", TestPinKeys.pem()));

    EncryptedPin pin = pins.encrypt("48261");

    assertThat(pin.keyId()).isEqualTo("PIN_TRANSPORT.v3");
    assertThat(TestPinKeys.decrypt(pin)).isEqualTo("48261");
  }

  @Test
  void theKeyIsAskedOncePerTtlAndAgainAfterARefresh() {
    when(auth.pinKey()).thenReturn(new PinKey("PIN_TRANSPORT.v1", "RSA-OAEP-SHA-256", TestPinKeys.pem()));

    pins.encrypt("48261");
    pins.encrypt("48261");
    verify(auth, times(1)).pinKey();

    clock.now = clock.now.plus(Duration.ofMinutes(10));
    pins.encrypt("48261");
    verify(auth, times(2)).pinKey();

    pins.refresh();
    pins.encrypt("48261");
    verify(auth, times(3)).pinKey();
  }

  @Test
  void mwAuthDownAtStartDoesNotFailTheStartButThePinStep() {
    when(auth.pinKey()).thenThrow(new MwException(ErrorCodes.SYS_AUTH_UNAVAILABLE, "down"));

    pins.warmUp();

    assertThatThrownBy(() -> pins.encrypt("48261")).isInstanceOf(MwException.class);
  }
}
