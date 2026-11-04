package mfs.mw.simulators.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import mfs.mw.simulators.dto.MerchantIpnScenario;
import mfs.mw.simulators.dto.ReceivedIpn;
import org.junit.jupiter.api.Test;

/** The merchant IPN receiver (ticket 32): answers as told, records every IPN it was sent. */
class MerchantIpnServiceTest {

  private final MerchantIpnService merchant =
      new MerchantIpnService(Clock.fixed(Instant.parse("2026-11-05T10:00:00Z"), ZoneOffset.UTC), 100);

  private int ipn(String id) {
    return merchant.receive("/merchant/ipn", Map.of("x-upay-ipn-id", id, "x-upay-signature", "ab"), "{\"sessionId\":\"" + id + "\"}");
  }

  @Test
  void acceptsByDefault() {
    assertThat(ipn("s1")).isEqualTo(200);
    assertThat(merchant.received(null)).singleElement().satisfies(r -> {
      assertThat(r.answeredStatus()).isEqualTo(200);
      assertThat(r.ipnId()).isEqualTo("s1");
      assertThat(r.headers()).containsEntry("x-upay-signature", "ab");
      assertThat(r.body()).isEqualTo("{\"sessionId\":\"s1\"}");
      assertThat(r.receivedAt()).isEqualTo(Instant.parse("2026-11-05T10:00:00Z"));
    });
  }

  @Test
  void failsTheNextNThenAccepts() {
    merchant.setScenario(new MerchantIpnScenario(2, 503, null));

    assertThat(ipn("s1")).isEqualTo(503);
    assertThat(ipn("s1")).isEqualTo(503);
    assertThat(ipn("s1")).isEqualTo(200);
    assertThat(ipn("s2")).isEqualTo(200);

    assertThat(merchant.received("s1")).extracting(ReceivedIpn::answeredStatus).containsExactly(503, 503, 200);
    assertThat(merchant.received("s1")).extracting(ReceivedIpn::seq).containsExactly(1L, 2L, 3L);
    assertThat(merchant.scenario().failNext()).isZero();
  }

  @Test
  void aScenarioForOneIpnLeavesOthersAlone() {
    merchant.setScenario(new MerchantIpnScenario(1, 502, "s2"));

    assertThat(ipn("s1")).isEqualTo(200);
    assertThat(ipn("s2")).isEqualTo(502);
    assertThat(ipn("s2")).isEqualTo(200);
  }

  @Test
  void aFailureWithoutAStatusIs500AndResetForgetsEverything() {
    merchant.setScenario(new MerchantIpnScenario(1, null, null));
    assertThat(ipn("s1")).isEqualTo(500);

    merchant.reset();

    assertThat(merchant.received(null)).isEmpty();
    assertThat(ipn("s1")).isEqualTo(200);
  }
}
