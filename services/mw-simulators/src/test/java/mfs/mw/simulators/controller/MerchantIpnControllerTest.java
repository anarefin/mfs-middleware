package mfs.mw.simulators.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import mfs.mw.simulators.service.MerchantIpnService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** The merchant IPN receiver's HTTP surface (ticket 32): the IPN endpoint and its control API. */
@WebMvcTest(MerchantIpnController.class)
@Import(MerchantIpnControllerTest.Fixtures.class)
class MerchantIpnControllerTest {

  @TestConfiguration(proxyBeanMethods = false)
  static class Fixtures {
    @Bean
    MerchantIpnService merchantIpnService() {
      return new MerchantIpnService(Clock.fixed(Instant.parse("2026-11-05T10:00:00Z"), ZoneOffset.UTC), 50);
    }
  }

  private final MockMvcTester mvc;
  private final MerchantIpnService merchant;

  @Autowired
  MerchantIpnControllerTest(MockMvcTester mvc, MerchantIpnService merchant) {
    this.mvc = mvc;
    this.merchant = merchant;
  }

  @BeforeEach
  void reset() {
    merchant.reset();
  }

  private MvcTestResult ipn(String id) {
    return mvc.post().uri("/merchant/ipn/shop1").contentType(MediaType.APPLICATION_JSON)
        .header("X-Bits-Ipn-Id", id).header("X-Bits-Signature", "abc").header("X-Bits-Timestamp", "1")
        .content("{\"sessionId\":\"" + id + "\"}").exchange();
  }

  @Test
  void theMerchantFailsAsToldThenAcceptsAndListsWhatItReceived() {
    assertThat(mvc.post().uri("/sim/merchant/ipn/scenario").contentType(MediaType.APPLICATION_JSON)
        .content("{\"failNext\":2,\"failStatus\":503}").exchange()).hasStatusOk();

    assertThat(ipn("s1")).hasStatus(503);
    assertThat(ipn("s1")).hasStatus(503);
    assertThat(ipn("s1")).hasStatusOk();
    assertThat(ipn("s2")).hasStatusOk();

    MvcTestResult list = mvc.get().uri("/sim/merchant/ipn/received?ipnId=s1").exchange();
    assertThat(list).hasStatusOk();
    assertThat(list).bodyJson().extractingPath("$.length()").isEqualTo(3);
    assertThat(list).bodyJson().extractingPath("$[2].answeredStatus").isEqualTo(200);
    assertThat(list).bodyJson().extractingPath("$[2].path").isEqualTo("/merchant/ipn/shop1");
    assertThat(list).bodyJson().extractingPath("$[2].headers.x-bits-signature").isEqualTo("abc");
    assertThat(list).bodyJson().extractingPath("$[2].body").isEqualTo("{\"sessionId\":\"s1\"}");

    assertThat(mvc.delete().uri("/sim/merchant/ipn/received").exchange()).hasStatus(204);
    assertThat(mvc.get().uri("/sim/merchant/ipn/received").exchange()).bodyJson().extractingPath("$.length()").isEqualTo(0);
  }

  @Test
  void anInvalidScenarioIsRefused() {
    assertThat(mvc.post().uri("/sim/merchant/ipn/scenario").contentType(MediaType.APPLICATION_JSON)
        .content("{\"failNext\":-1}").exchange()).hasStatus(400);
  }
}
