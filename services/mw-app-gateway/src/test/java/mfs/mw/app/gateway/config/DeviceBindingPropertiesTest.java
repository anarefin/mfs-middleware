package mfs.mw.app.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** A nonce is remembered longer than any timestamp signed with it can be accepted. */
class DeviceBindingPropertiesTest {

  @Test
  void theNonceTtlIsTwiceTheSkewPlusAMargin() {
    DeviceBindingProperties p =
        new DeviceBindingProperties(true, Duration.ofMinutes(5), Duration.ofMinutes(5), Map.of(), true,
            new DeviceBindingProperties.Otp(Duration.ofMinutes(2), 3, 5, Duration.ofMinutes(15)), "mw.notify.request.v1");
    // a timestamp is accepted from skew before to skew after it: 2x skew, plus 30 s for clock and expiry jitter
    assertThat(p.nonceTtl()).isEqualTo(Duration.ofMinutes(10).plusSeconds(30));
    assertThat(p.nonceTtl()).isGreaterThan(p.clockSkew().multipliedBy(2));
  }
}
