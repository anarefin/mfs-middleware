package mfs.mw.core.mock.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

import mfs.mw.core.mock.dto.WalletBalance;
import mfs.mw.core.mock.service.WalletBalances;
import mfs.mw.core.mock.service.WalletSeedService;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/**
 * The test-only seed API (ticket 57, contracts/api/mw-core-mock/core-mock-seed.v1.openapi.yaml):
 * {@code PUT /core-mock/v1/wallets/{walletId}} only with {@code mw.coremock.seed-api.enabled=true}.
 */
class WalletSeedControllerTest {

  private static final String URI = "/core-mock/v1/wallets/";
  private static final WalletBalance NEW = new WalletBalance("01712000001", "CUSTOMER", "ACTIVE", 1000, 0, 1000);

  @Nested
  @WebMvcTest(
      controllers = {WalletSeedController.class, WalletController.class},
      properties = "mw.coremock.seed-api.enabled=true")
  class SwitchedOn {

    private final MockMvcTester mvc;
    @MockitoBean WalletSeedService seeds;
    @MockitoBean WalletBalances balances;

    @Autowired
    SwitchedOn(MockMvcTester mvc) {
      this.mvc = mvc;
    }

    private MockMvcTester.MockMvcRequestBuilder put(String id, String body) {
      return mvc.put().uri(URI + id).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @Test
    void aNewWalletIs201WithItsView() {
      given(seeds.seed("01712000001", "CUSTOMER", 1000)).willReturn(new WalletSeedService.Seeded(true, NEW));

      assertThat(put("01712000001", "{\"type\":\"CUSTOMER\",\"openingBalanceMinor\":1000}"))
          .hasStatus(201)
          .bodyJson()
          .isLenientlyEqualTo(
              "{\"walletId\":\"01712000001\",\"type\":\"CUSTOMER\",\"status\":\"ACTIVE\",\"balanceMinor\":1000,"
                  + "\"heldMinor\":0,\"availableMinor\":1000}");
    }

    @Test
    void anExistingWalletIs200AsItIs() {
      WalletBalance moved = new WalletBalance("01712000001", "CUSTOMER", "ACTIVE", 42, 0, 42);
      given(seeds.seed(eq("01712000001"), any(), anyLong())).willReturn(new WalletSeedService.Seeded(false, moved));

      assertThat(put("01712000001", "{\"type\":\"CUSTOMER\",\"openingBalanceMinor\":1000}"))
          .hasStatus(200)
          .bodyJson()
          .extractingPath("$.balanceMinor")
          .isEqualTo(42);
    }

    @Test
    void aBadBodyIs400AndSeedsNothing() {
      assertThat(put("01712000001", "{\"type\":\"SYSTEM\",\"openingBalanceMinor\":1}"))
          .hasStatus(400).bodyJson().extractingPath("$.code").isEqualTo("MW-VAL-001");
      assertThat(put("01712000001", "{\"type\":\"CUSTOMER\",\"openingBalanceMinor\":-1}")).hasStatus(400);
      verifyNoInteractions(seeds);
    }
  }

  @Nested
  @WebMvcTest(controllers = {WalletSeedController.class, WalletController.class})
  class SwitchedOff {

    private final MockMvcTester mvc;
    @MockitoBean WalletBalances balances;

    @Autowired
    SwitchedOff(MockMvcTester mvc) {
      this.mvc = mvc;
    }

    @Test
    void thereIsNoPutAtAll() {
      assertThat(mvc.put().uri(URI + "01712000001").contentType(MediaType.APPLICATION_JSON)
              .content("{\"type\":\"CUSTOMER\",\"openingBalanceMinor\":1000}"))
          .hasStatus(405);
    }
  }
}
