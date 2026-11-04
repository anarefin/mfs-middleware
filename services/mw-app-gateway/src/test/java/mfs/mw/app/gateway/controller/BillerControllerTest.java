package mfs.mw.app.gateway.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import java.util.List;
import java.util.Map;
import mfs.mw.app.gateway.config.SecurityConfig;
import mfs.mw.app.gateway.dto.Amount;
import mfs.mw.app.gateway.dto.Bill;
import mfs.mw.app.gateway.dto.Biller;
import mfs.mw.app.gateway.exception.MwException;
import mfs.mw.app.gateway.security.AppUserResolver;
import mfs.mw.app.gateway.service.BillerService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** {@code GET /app/v1/billers} and {@code POST /app/v1/billers/{code}/fetch-bill} (spec 9.1, ticket 21). */
@WebMvcTest(BillerController.class)
@Import({SecurityConfig.class, AppUserResolver.class})
class BillerControllerTest {

  private final MockMvcTester mvc;

  @MockitoBean BillerService service;

  @Autowired
  BillerControllerTest(MockMvcTester mvc) {
    this.mvc = mvc;
  }

  static RequestPostProcessor customer() {
    return jwt()
        .jwt(j -> j.claim("msisdn", "01711000001").claim("realm_access", Map.of("roles", List.of("customer"))));
  }

  MvcTestResult fetch(RequestPostProcessor auth, String code, String body) {
    var req =
        mvc.post().uri("/app/v1/billers/{code}/fetch-bill", code).contentType(MediaType.APPLICATION_JSON).content(body);
    return (auth == null ? req : req.with(auth)).exchange();
  }

  @Test
  void listsTheBillers() {
    when(service.billers()).thenReturn(List.of(new Biller("DESCO", "Dhaka Electric", "ELECTRICITY", null)));
    MvcTestResult r = mvc.get().uri("/app/v1/billers").with(customer()).exchange();
    assertThat(r).hasStatusOk();
    assertThat(r).bodyJson().extractingPath("$.billers[0].code").isEqualTo("DESCO");
    assertThat(r).bodyJson().doesNotHavePath("$.billers[0].partnerId");
  }

  @Test
  void fetchesABill() {
    when(service.fetch("DESCO", "1234567"))
        .thenReturn(new Bill("DESCO", "1234567", new Amount(66_700L, "BDT"), "2026-11-14", "2026-11"));
    MvcTestResult r = fetch(customer(), "DESCO", "{\"billNumber\":\"1234567\"}");
    assertThat(r).hasStatusOk();
    assertThat(r).bodyJson().extractingPath("$.amount.valueMinor").isEqualTo(66700);
    assertThat(r).bodyJson().extractingPath("$.billNumber").isEqualTo("1234567");
  }

  @Test
  void aBadBillNumberOrBillerCodeIs400AndNothingIsAsked() {
    assertThat(fetch(customer(), "DESCO", "{\"billNumber\":\"\"}")).hasStatus(400);
    assertThat(fetch(customer(), "DESCO", "{\"billNumber\":\"12 34\"}")).hasStatus(400);
    assertThat(fetch(customer(), "desco!", "{\"billNumber\":\"1234567\"}")).hasStatus(400);
    verify(service, never()).fetch(anyString(), anyString());
  }

  @Test
  void aMappedPartnerErrorIsTheEnvelope() {
    when(service.fetch("DESCO", "1234404"))
        .thenThrow(new MwException("MW-PTR-BILLAGG-BILL_NOT_FOUND", "Bill not found").withStatus(HttpStatus.BAD_GATEWAY));
    MvcTestResult r = fetch(customer(), "DESCO", "{\"billNumber\":\"1234404\"}");
    assertThat(r).hasStatus(502);
    assertThat(r).bodyJson().extractingPath("$.code").isEqualTo("MW-PTR-BILLAGG-BILL_NOT_FOUND");
  }

  @Test
  void noTokenIs401() {
    assertThat(mvc.get().uri("/app/v1/billers").exchange()).hasStatus(401);
    assertThat(fetch(null, "DESCO", "{\"billNumber\":\"1234567\"}")).hasStatus(401);
  }
}
