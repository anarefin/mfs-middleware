package mfs.mw.simulators.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import mfs.mw.simulators.client.InMemoryLmkStore;
import mfs.mw.simulators.client.LmkStore;
import mfs.mw.simulators.service.PayShieldKeys;
import mfs.mw.simulators.service.PayShieldScenarioStore;
import mfs.mw.simulators.service.PayShieldService;
import mfs.mw.simulators.util.PinBlocks;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/** {@code /sim/payshield/scenario} (ticket 64): set, read, reset; a bad error code is 400. */
@WebMvcTest(value = PayShieldController.class, properties = "spring.test.mockmvc.print=none")
@Import({PayShieldScenarioStore.class, PayShieldService.class, PayShieldKeys.class,
    PayShieldControllerTest.Lmk.class})
class PayShieldControllerTest {

  @TestConfiguration(proxyBeanMethods = false)
  static class Lmk {
    @Bean
    LmkStore lmkStore() {
      return new InMemoryLmkStore();
    }
  }

  final MockMvcTester mvc;
  final PayShieldScenarioStore store;
  final PayShieldKeys keys;

  @Autowired
  PayShieldControllerTest(MockMvcTester mvc, PayShieldScenarioStore store, PayShieldKeys keys) {
    this.mvc = mvc;
    this.store = store;
    this.keys = keys;
  }

  String helper(String blob, String format) {
    return "{\"keyType\":\"TPK\",\"key\":\"" + blob + "\",\"format\":\"" + format
        + "\",\"pin\":\"1234\",\"pan\":\"4111111111111111\"}";
  }

  @Test
  void pinBlockHelperEnciphersUnderTheGivenKey() throws Exception {
    byte[] tpk = HexFormat.of().parseHex("0123456789ABCDEFFEDCBA9876543210");
    String blob = keys.wrap(PayShieldKeys.KeyType.TPK, tpk, 'U');
    var r = mvc.post().uri("/sim/payshield/pinblock").contentType(MediaType.APPLICATION_JSON)
        .content(helper(blob, "ISO0")).exchange();
    assertThat(r).hasStatusOk();
    String block = JsonPath.read(r.getResponse().getContentAsString(StandardCharsets.UTF_8), "$.block");
    assertThat(PinBlocks.decipher(PinBlocks.Format.ISO0, HexFormat.of().parseHex(block), "4111111111111111", tpk))
        .isEqualTo("1234");
    assertThat(mvc.post().uri("/sim/payshield/pinblock").contentType(MediaType.APPLICATION_JSON)
        .content(helper(blob, "ISO4"))).hasStatus(400);
  }

  @Test
  void setReadAndReset() {
    assertThat(mvc.post().uri("/sim/payshield/scenario").contentType(MediaType.APPLICATION_JSON)
        .content("{\"latencyMs\":50,\"errorCode\":\"68\",\"commands\":[\"DG\"]}")).hasStatusOk();
    assertThat(store.get().errorFor("DG")).isEqualTo("68");
    assertThat(store.get().errorFor("NC")).isNull();
    assertThat(mvc.get().uri("/sim/payshield/scenario")).bodyJson().extractingPath("$.latencyMs").isEqualTo(50);
    assertThat(mvc.delete().uri("/sim/payshield/scenario")).hasStatus(204);
    assertThat(store.get().errorFor("DG")).isNull();
  }

  @Test
  void aBadErrorCodeIsRefused() {
    assertThat(mvc.post().uri("/sim/payshield/scenario").contentType(MediaType.APPLICATION_JSON)
        .content("{\"errorCode\":\"x\"}")).hasStatus(400);
  }
}
