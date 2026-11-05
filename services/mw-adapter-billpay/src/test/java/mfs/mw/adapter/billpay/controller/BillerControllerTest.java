package mfs.mw.adapter.billpay.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import mfs.mw.adapter.billpay.dto.BillView;
import mfs.mw.adapter.billpay.dto.BillerView;
import mfs.mw.adapter.billpay.dto.Money;
import mfs.mw.adapter.billpay.exception.MwException;
import mfs.mw.adapter.billpay.service.BillFetchService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** Internal API for mw-app-gateway (contract api/mw-adapter-billpay/billpay.v1.openapi.yaml). */
@WebMvcTest(BillerController.class)
class BillerControllerTest {

  private final MockMvcTester mvc;

  @MockitoBean BillFetchService service;

  @Autowired
  BillerControllerTest(MockMvcTester mvc) {
    this.mvc = mvc;
  }

  MvcTestResult fetch(String code, String body) {
    return mvc.post()
        .uri("/billpay/v1/billers/{code}/bills/fetch", code)
        .contentType(MediaType.APPLICATION_JSON)
        .content(body)
        .exchange();
  }

  @Test
  void listsTheCatalogueWithTheAggregator() {
    when(service.billers()).thenReturn(List.of(new BillerView("DESCO", "Dhaka Electric", "ELECTRICITY", "billagg")));
    MvcTestResult r = mvc.get().uri("/billpay/v1/billers").exchange();
    assertThat(r).hasStatusOk();
    assertThat(r).bodyJson().extractingPath("$[0].partnerId").isEqualTo("billagg");
  }

  @Test
  void fetchesABill() {
    when(service.fetch("DESCO", "1234567"))
        .thenReturn(new BillView("DESCO", "1234567", new Money(66_700L, "BDT"), "2026-11-14", "2026-11"));
    MvcTestResult r = fetch("DESCO", "{\"billNumber\":\"1234567\"}");
    assertThat(r).hasStatusOk();
    assertThat(r).bodyJson().extractingPath("$.amount.valueMinor").isEqualTo(66700);
  }

  @Test
  void badInputIs400AndNothingIsAsked() {
    assertThat(fetch("DESCO", "{\"billNumber\":\"12 34\"}")).hasStatus(400);
    assertThat(fetch("DESCO", "{}")).hasStatus(400);
    assertThat(fetch("de sco", "{\"billNumber\":\"1\"}")).hasStatus(400);
    verify(service, never()).fetch(anyString(), anyString());
  }

  @Test
  void aRefusalIsTheEnvelopeWithItsStatus() {
    when(service.fetch("DESCO", "1234404"))
        .thenThrow(new MwException("MW-PTR-BILLAGG-BILL_NOT_FOUND", "Bill not found").withStatus(HttpStatus.BAD_GATEWAY));
    MvcTestResult r = fetch("DESCO", "{\"billNumber\":\"1234404\"}");
    assertThat(r).hasStatus(502);
    assertThat(r).bodyJson().extractingPath("$.code").isEqualTo("MW-PTR-BILLAGG-BILL_NOT_FOUND");
  }
}
