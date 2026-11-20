package mfs.mw.notify.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import mfs.mw.notify.TestInfrastructure;
import mfs.mw.notify.dto.Channel;
import mfs.mw.notify.dto.NotificationRequest;
import mfs.mw.notify.dto.Priority;
import mfs.mw.notify.service.NotificationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * {@code GET /internal/v1/inbox} and {@code POST /internal/v1/inbox/{id}/read} (ticket 107a): OTP
 * messages are left out, pages stay inside the requested MSISDN, and the MSISDN in the body is masked.
 */
@SpringBootTest(properties = {
    "mw.notify.consumers.enabled=false",
    "mw.notify.smpp.enabled=false",
    "mw.notify.dispatch.enabled=false"
})
@AutoConfigureMockMvc(addFilters = false)
@Import(TestInfrastructure.class)
class InboxControllerIT {

  private static final String MINE = "01711000077";
  private static final String OTHER = "01711000078";
  private static final Instant T0 = Instant.parse("2026-11-20T08:00:00Z");

  private final MockMvcTester mvc;
  private final NotificationService notifications;
  private final JdbcTemplate jdbc;

  @Autowired
  InboxControllerIT(MockMvcTester mvc, NotificationService notifications, JdbcTemplate jdbc) {
    this.mvc = mvc;
    this.notifications = notifications;
    this.jdbc = jdbc;
  }

  @Test
  void otpIsOmittedAndOnlyTheRequestedMsisdnIsPagedAndMasked() {
    String older = "inbox-older";
    String mid = "inbox-mid";
    String newest = "inbox-newest";
    String otp = "inbox-otp";
    String other = "inbox-other";
    notifications.accept(request(older, MINE, Priority.TXN));
    notifications.accept(request(mid, MINE, Priority.INFO));
    notifications.accept(request(newest, MINE, Priority.TXN));
    notifications.accept(request(otp, MINE, Priority.OTP));
    notifications.accept(request(other, OTHER, Priority.TXN));
    sent(older, T0);
    sent(mid, T0.plusSeconds(1));
    sent(newest, T0.plusSeconds(2));
    sent(otp, T0.plusSeconds(3));
    sent(other, T0.plusSeconds(4));

    MvcTestResult first = mvc.get().uri("/internal/v1/inbox")
        .param("msisdn", "8801711000077").param("limit", "2").param("offset", "0").exchange();
    assertThat(first).hasStatusOk();
    String page = text(first);
    assertThat(page).contains(newest).contains(mid);
    assertThat(page).doesNotContain(older).doesNotContain(otp).doesNotContain(other).doesNotContain(MINE);
    assertThat(first).bodyJson().extractingPath("$.items[0].msisdn").isEqualTo("0171****077");
    assertThat(first).bodyJson().extractingPath("$.items[0].body").isNotEqualTo("****");
    assertThat(first).bodyJson().extractingPath("$.limit").isEqualTo(2);

    MvcTestResult second = mvc.get().uri("/internal/v1/inbox")
        .param("msisdn", MINE).param("limit", "2").param("offset", "2").exchange();
    assertThat(second).hasStatusOk();
    assertThat(text(second)).contains(older).doesNotContain(newest).doesNotContain(otp);

    MvcTestResult missing = mvc.post().uri("/internal/v1/inbox/{id}/read", other).param("msisdn", MINE).exchange();
    assertThat(missing).hasStatus(404).bodyJson().extractingPath("$.code").isEqualTo("MW-VAL-003");
    assertThat(readAt(other)).isNull();

    MvcTestResult otpRead = mvc.post().uri("/internal/v1/inbox/{id}/read", otp).param("msisdn", MINE).exchange();
    assertThat(otpRead).hasStatus(404);
    assertThat(readAt(otp)).isNull();

    MvcTestResult read = mvc.post().uri("/internal/v1/inbox/{id}/read", newest).param("msisdn", MINE).exchange();
    assertThat(read).hasStatus(204);
    assertThat(readAt(newest)).isNotNull();

    MvcTestResult again = mvc.get().uri("/internal/v1/inbox")
        .param("msisdn", MINE).param("limit", "1").param("offset", "0").exchange();
    assertThat(again).bodyJson().extractingPath("$.items[0].read").isEqualTo(true);
  }

  private static NotificationRequest request(String id, String msisdn, Priority priority) {
    return new NotificationRequest(
        1, id, null, "corr-" + id, new NotificationRequest.Recipient(msisdn, null, null, null),
        "TXN_P2P_SENT", "en",
        Map.of("amount", "10.00", "counterparty", "01811000002", "fee", "0.00", "txnId", "trx-inbox"),
        List.of(Channel.SMS), priority, null, null);
  }

  private static String text(MvcTestResult result) {
    return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
  }

  private void sent(String id, Instant at) {
    jdbc.update("update message set status = 'SENT', created_at = ? where message_id = ?", Timestamp.from(at), id);
  }

  private Instant readAt(String id) {
    Timestamp at = jdbc.queryForObject("select read_at from message where message_id = ?", Timestamp.class, id);
    return at == null ? null : at.toInstant();
  }
}
