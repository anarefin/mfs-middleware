package mfs.mw.notify.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import mfs.mw.notify.dto.NotificationLogItem;
import mfs.mw.notify.dto.NotificationLogQuery;
import mfs.mw.notify.repository.NotificationLogRepository;
import mfs.mw.notify.repository.NotificationLogRepository.Row;
import mfs.mw.notify.service.NotificationLogService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** {@code GET /internal/v1/notifications} filters and masks (ticket 111, NOT-08). */
@WebMvcTest(NotificationLogController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(NotificationLogService.class)
class NotificationLogControllerTest {

  static final Instant AT = Instant.parse("2026-11-20T08:00:00Z");

  private final MockMvcTester mvc;

  @MockitoBean NotificationLogRepository messages;

  @Autowired
  NotificationLogControllerTest(MockMvcTester mvc) {
    this.mvc = mvc;
  }

  @Test
  void filtersArePassedThroughAndAnOtpBodyIsMasked() {
    when(messages.search(any())).thenReturn(List.of(
        new Row("m-otp", "txn-9", "SMS", "01711000001", "OTP", "SENT", "LOGIN_OTP", "Your Bits code is 123456", AT),
        new Row("m-txn", "txn-9", "SMS", "01711000001", "TXN", "SENT", "TXN_P2P_SENT", "You sent Tk 10.00", AT)));

    MvcTestResult result = mvc.get().uri("/internal/v1/notifications")
        .param("msisdn", "8801711000001")
        .param("txnId", "txn-9")
        .param("channel", "SMS")
        .param("status", "SENT")
        .param("limit", "10")
        .param("offset", "0")
        .exchange();
    assertThat(result).hasStatusOk().bodyJson().extractingPath("items[0].body").isEqualTo(NotificationLogItem.OTP_BODY);
    assertThat(result).bodyJson().extractingPath("items[0].msisdn").isEqualTo("0171****001");
    assertThat(result).bodyJson().extractingPath("items[1].body").isEqualTo("You sent Tk 10.00");
    assertThat(result).bodyJson().extractingPath("items[1].msisdn").isEqualTo("0171****001");

    ArgumentCaptor<NotificationLogQuery> query = ArgumentCaptor.forClass(NotificationLogQuery.class);
    verify(messages).search(query.capture());
    assertThat(query.getValue().msisdn()).isEqualTo("01711000001");
    assertThat(query.getValue().txnId()).isEqualTo("txn-9");
    assertThat(query.getValue().channel()).isEqualTo("SMS");
    assertThat(query.getValue().status()).isEqualTo("SENT");
    assertThat(query.getValue().limit()).isEqualTo(10);
  }
}
