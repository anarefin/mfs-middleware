package mfs.mw.adapter.telco.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import mfs.mw.adapter.telco.dto.Money;
import mfs.mw.adapter.telco.dto.PostpaidBillView;
import mfs.mw.adapter.telco.service.PostpaidBillService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/** {@code POST /telco/v1/bills/fetch} (ticket 112b). */
@WebMvcTest(PostpaidBillController.class)
class PostpaidBillControllerTest {

  private final MockMvcTester mvc;

  @MockitoBean PostpaidBillService service;

  @Autowired
  PostpaidBillControllerTest(MockMvcTester mvc) {
    this.mvc = mvc;
  }

  @Test
  void aBillIsTheTwoAmounts() {
    when(service.fetch("gp-ocs", "01711000021"))
        .thenReturn(new PostpaidBillView(new Money(45_000, "BDT"), new Money(5_000, "BDT")));

    assertThat(mvc.post().uri("/telco/v1/bills/fetch").contentType(MediaType.APPLICATION_JSON)
        .content("{\"partnerId\":\"gp-ocs\",\"msisdn\":\"01711000021\"}"))
        .hasStatus(200)
        .bodyJson().extractingPath("outstanding.valueMinor").isEqualTo(45_000);
  }

  @Test
  void aBadNumberIsRejectedBeforeTheService() {
    assertThat(mvc.post().uri("/telco/v1/bills/fetch").contentType(MediaType.APPLICATION_JSON)
        .content("{\"partnerId\":\"gp-ocs\",\"msisdn\":\"not-a-number\"}"))
        .hasStatus(400);
    org.mockito.Mockito.verify(service, org.mockito.Mockito.never()).fetch(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
  }
}
