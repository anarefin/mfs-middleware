package mfs.mw.notify.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import mfs.mw.notify.MutableClock;
import mfs.mw.notify.TestInfrastructure;
import mfs.mw.notify.client.FakeSmsSender;
import mfs.mw.notify.dto.ConfigChangedEvent;
import mfs.mw.notify.dto.ConfigChangedEvent.Change;
import mfs.mw.notify.dto.ConfigChangedEvent.ConfigType;
import mfs.mw.notify.dto.OptOutEvent;
import mfs.mw.notify.dto.Priority;
import mfs.mw.notify.dto.WalletLockEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * A campaign queues at most its per-second throttle, and an OTP accepted while it runs is
 * submitted first (ticket 111, NOT-07). The clock moves the window; nothing sleeps.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "mw.notify.consumers.enabled=false",
      "mw.notify.smpp.enabled=false",
      "mw.notify.dispatch.enabled=false",
      "mw.notify.campaign.enabled=false"
    })
@Import({TestInfrastructure.class, NotificationFlowIT.Fixtures.class})
class CampaignRunnerIT {

  static final Instant T0 = NotificationFlowIT.T0;

  static final List<String> EIGHT = List.of(
      "01711000201", "01711000202", "01711000203", "01711000204",
      "01711000205", "01711000206", "01711000207", "01711000208");

  private final CampaignService campaigns;
  private final NotificationService notifications;
  private final DispatchService dispatcher;
  private final OptOutService optOuts;
  private final WalletLockService locks;
  private final FakeSmsSender smsc;
  private final MutableClock clock;
  private final JdbcTemplate jdbc;

  @Autowired
  CampaignRunnerIT(CampaignService campaigns, NotificationService notifications, DispatchService dispatcher,
      OptOutService optOuts, WalletLockService locks, FakeSmsSender smsc, MutableClock clock, JdbcTemplate jdbc) {
    this.campaigns = campaigns;
    this.notifications = notifications;
    this.dispatcher = dispatcher;
    this.optOuts = optOuts;
    this.locks = locks;
    this.smsc = smsc;
    this.clock = clock;
    this.jdbc = jdbc;
  }

  @BeforeEach
  void reset() {
    smsc.reset();
    clock.set(T0);
    jdbc.update("update message set status = 'FAILED' where status in ('QUEUED', 'RETRY')");
    jdbc.update("update campaign set status = 'CANCELLED' where status in ('APPROVED', 'RUNNING')");
  }

  @Test
  void theThrottleHoldsAndAnOtpSubmittedDuringTheCampaignGoesFirst() {
    campaigns.onConfigChanged(approved("camp-throttle", 4, EIGHT));

    assertThat(campaigns.run()).isEqualTo(4);
    assertThat(campaigns.run()).isZero();

    notifications.accept(NotificationFlowIT.request("otp-during-campaign", "01711000999", "en", Priority.OTP));
    dispatcher.dispatchDue();

    assertThat(smsc.sent()).hasSize(5);
    assertThat(smsc.sent().get(0).priority()).isEqualTo(Priority.OTP);
    assertThat(smsc.sent().stream().filter(s -> s.priority() == Priority.PROMO)).hasSize(4);

    clock.set(T0.plusSeconds(1));
    assertThat(campaigns.run()).isEqualTo(4);
    assertThat(status("camp-throttle")).isEqualTo("DONE");
  }

  @Test
  void aHighRateFinishesAShortListInOneTick() {
    campaigns.onConfigChanged(approved("camp-fast", 100, EIGHT));

    assertThat(campaigns.run()).isEqualTo(8);
    assertThat(status("camp-fast")).isEqualTo("DONE");
  }

  @Test
  void anOptedOutMsisdnGetsNoPromoButStillGetsTheTxnSms() {
    optOuts.onOptOut(new OptOutEvent(1, "01711000077", "APP", T0, "corr-opt"));
    notifications.accept(NotificationFlowIT.request("promo-opted-out", "01711000077", "en", Priority.PROMO));
    notifications.accept(NotificationFlowIT.request("txn-opted-out", "01711000077", "en", Priority.TXN));

    dispatcher.dispatchDue();

    assertThat(smsc.sent()).singleElement().satisfies(s -> {
      assertThat(s.priority()).isEqualTo(Priority.TXN);
      assertThat(s.part().destination()).isEqualTo("01711000077");
    });
    assertThat(messageStatus("promo-opted-out")).isEqualTo("SUPPRESSED");
    assertThat(messageStatus("txn-opted-out")).isEqualTo("SENT");
  }

  @Test
  void aLockNoticeIsSentAndAPinResetIsNot() {
    assertThat(locks.onCommand(new WalletLockEvent(1, "cmd-lock", "LOCK", "01711000002", "reason", "c"))).isEqualTo(1);
    assertThat(locks.onCommand(new WalletLockEvent(1, "cmd-pin", "PIN_RESET", "01711000002", "reason", "c"))).isZero();

    dispatcher.dispatchDue();

    assertThat(messageStatus("cmd-lock:sms:wallet-locked-sms")).isEqualTo("SENT");
    assertThat(jdbc.queryForObject("select count(*) from message where message_id like 'cmd-pin%'", Integer.class))
        .isZero();
  }

  private String status(String campaignId) {
    return jdbc.queryForObject("select status from campaign where campaign_id = ?", String.class, campaignId);
  }

  private String messageStatus(String messageId) {
    return jdbc.queryForObject("select status from message where message_id = ?", String.class, messageId);
  }

  private static ConfigChangedEvent approved(String id, int throttle, List<String> msisdns) {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("templateCode", "CAMPAIGN_PROMO");
    value.put("locale", "en");
    value.put("scheduleAt", T0.toString());
    value.put("throttlePerSecond", throttle);
    value.put("text", "a short offer");
    value.put("msisdns", msisdns);
    return new ConfigChangedEvent(1, CampaignService.KEY_PREFIX + id, ConfigType.FEATURE_FLAG, Change.UPSERT, 1,
        value, T0, "maker", "checker", "corr-" + id);
  }
}
