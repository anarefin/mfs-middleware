package mfs.mw.crypto.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import mfs.mw.crypto.client.FakeCryptoProvider;
import org.junit.jupiter.api.Test;

class HsmHealthServiceTest {

  @Test
  void theGaugeFollowsTheProbe() {
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    FakeCryptoProvider hsm = new FakeCryptoProvider();
    HsmHealthService health = new HsmHealthService(List.of(hsm), meters);
    double before = meters.get("crypto.hsm.up").tag("provider", "pkcs11").gauge().value();

    health.probe();
    double up = meters.get("crypto.hsm.up").tag("provider", "pkcs11").gauge().value();
    hsm.down(true);
    health.probe();
    double down = meters.get("crypto.hsm.up").tag("provider", "pkcs11").gauge().value();

    assertThat(before).isZero();
    assertThat(up).isEqualTo(1.0);
    assertThat(down).isZero();
    assertThat(health.up("pkcs11")).isZero();
  }
}
