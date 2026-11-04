package mfs.mw.app.gateway.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import mfs.mw.app.gateway.config.SecurityConfig;
import mfs.mw.app.gateway.dto.Amount;
import mfs.mw.app.gateway.dto.AppUser;
import mfs.mw.app.gateway.dto.CollectionList;
import mfs.mw.app.gateway.dto.MerchantCollection;
import mfs.mw.app.gateway.exception.MwException;
import mfs.mw.app.gateway.security.AppUserResolver;
import mfs.mw.app.gateway.service.CollectionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** {@code GET /app/v1/merchant/collections} (ticket 30): JWT, the token's role and wallet, limit. */
@WebMvcTest(MerchantController.class)
@Import({SecurityConfig.class, AppUserResolver.class})
class MerchantControllerTest {

  static final AppUser MERCHANT = new AppUser("01911000001", "MERCHANT_APP", "MERCHANT");

  private final MockMvcTester mvc;

  @MockitoBean CollectionService service;

  @Autowired
  MerchantControllerTest(MockMvcTester mvc) {
    this.mvc = mvc;
  }

  static RequestPostProcessor token(String msisdn, String role) {
    return jwt().jwt(j -> j.claim("msisdn", msisdn).claim("realm_access", Map.of("roles", List.of(role))));
  }

  MvcTestResult get(RequestPostProcessor auth, String query) {
    var req = mvc.get().uri("/app/v1/merchant/collections" + query).header("X-Correlation-Id", "corr-m");
    return (auth == null ? req : req.with(auth)).exchange();
  }

  @Test
  void theMerchantGetsItsCollectionsForTheWalletOfItsToken() {
    when(service.collections(eq(MERCHANT), isNull()))
        .thenReturn(
            new CollectionList(
                List.of(
                    new MerchantCollection(
                        "019a4f6e-1c2d-7a3b-8c4d-5e6f7a8b9c0d", "COMPLETED", new Amount(25_000L, "BDT"),
                        "0171****789", "CR-1", Instant.parse("2026-11-04T06:00:00Z"), null))));

    MvcTestResult r = get(token("+8801911000001", "merchant"), "");

    assertThat(r).hasStatus(200);
    assertThat(r).bodyJson().extractingPath("$.collections[0].payer").isEqualTo("0171****789");
    assertThat(r).bodyJson().extractingPath("$.collections[0].amount.valueMinor").isEqualTo(25_000);
    verify(service).collections(eq(MERCHANT), isNull());
  }

  @Test
  void anotherRoleIsRefusedByTheService() {
    AppUser customer = new AppUser("01711000001", "CUSTOMER_APP", "CUSTOMER");
    when(service.collections(eq(customer), any()))
        .thenThrow(new MwException("MW-AUTH-002", "Only a merchant can see collections").withStatus(HttpStatus.FORBIDDEN));

    MvcTestResult r = get(token("01711000001", "customer"), "");

    assertThat(r).hasStatus(403);
    assertThat(r).bodyJson().extractingPath("$.code").isEqualTo("MW-AUTH-002");
  }

  @Test
  void noTokenIs401() {
    MvcTestResult r = get(null, "");
    assertThat(r).hasStatus(401);
    assertThat(r).bodyJson().extractingPath("$.code").isEqualTo("MW-AUTH-001");
    verify(service, never()).collections(any(), any());
  }

  @Test
  void aLimitOutside1To100IsMwVal() {
    for (String bad : List.of("0", "101", "-1")) {
      MvcTestResult r = get(token("01911000001", "merchant"), "?limit=" + bad);
      assertThat(r).as(bad).hasStatus(400);
      assertThat(r).bodyJson().extractingPath("$.code").isEqualTo("MW-VAL-001");
    }
    verify(service, never()).collections(any(), any());

    when(service.collections(MERCHANT, 100)).thenReturn(new CollectionList(List.of()));
    assertThat(get(token("01911000001", "merchant"), "?limit=100")).hasStatus(200);
  }
}
