package mfs.mw.auth.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;
import mfs.mw.auth.TestInfrastructure;
import mfs.mw.auth.ValkeyInfrastructure;
import mfs.mw.auth.client.CryptoClient;
import mfs.mw.auth.dto.NotificationRequest;
import mfs.mw.auth.messaging.EventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * The checkout OTP (ticket 72, spec 9.3): {@code POST /internal/v1/otp} and {@code POST
 * /internal/v1/otp/verifications} with a services token of mw-checkout, purpose {@code CHECKOUT}
 * bound to the checkout session instead of a device, against real PostgreSQL. The SMS is read from
 * the mocked publisher (template {@code CHECKOUT_OTP} with amount and merchant). Security review of
 * ticket 72: a checkout OTP has its own slot ({@code channel_otp}, wallet + purpose + session) and its
 * own send quota, so it never replaces or uses up a device OTP (BIND/LOGIN/RESET) and the other way round.
 */
@SpringBootTest(properties = {"mw.outbox.relay.enabled=false", "spring.data.redis.timeout=3s"})
@AutoConfigureMockMvc
@Import({TestInfrastructure.class, ValkeyInfrastructure.class})
class CheckoutOtpIT {

  static final String SEND = "/internal/v1/otp";
  static final String VERIFY = "/internal/v1/otp/verifications";

  private final MockMvcTester mvc;
  private final JdbcTemplate jdbc;
  private final mfs.mw.auth.service.DeviceOtpService deviceOtps;

  @MockitoBean EventPublisher publisher;
  @MockitoBean CryptoClient crypto;

  String msisdn;
  String sessionId;

  @Autowired
  CheckoutOtpIT(MockMvcTester mvc, JdbcTemplate jdbc, mfs.mw.auth.service.DeviceOtpService deviceOtps) {
    this.mvc = mvc;
    this.jdbc = jdbc;
    this.deviceOtps = deviceOtps;
  }

  @BeforeEach
  void setUp() {
    when(crypto.tokeniseMsisdn(anyString()))
        .thenAnswer(inv -> new CryptoClient.MsisdnToken("tok-" + inv.getArgument(0), "tokenise-msisdn.v1"));
    msisdn = "0171" + String.format("%07d", (int) (Math.random() * 10_000_000));
    sessionId = UUID.randomUUID().toString();
    jdbc.update("insert into wallet (wallet_id, msisdn_token, msisdn_token_kid, msisdn_enc, type, status, has_pin, version)"
        + " values (?, ?, 'tokenise-msisdn.v1', '{\"kekId\":\"kek-column.v1\",\"wrappedKey\":\"d3JhcA==\",\"iv\":\"AA==\","
        + "\"ciphertext\":\"Yw==\",\"aad\":\"aWQ=\"}'::jsonb, 'CUSTOMER', 'ACTIVE', true, 1)", msisdn, "tok-" + msisdn);
  }

  String sendBody() {
    return "{\"msisdn\":\"" + msisdn + "\",\"purpose\":\"CHECKOUT\",\"bindingId\":\"" + sessionId
        + "\",\"locale\":\"en\",\"amount\":\"250.00\",\"merchant\":\"Demo Shop\"}";
  }

  String verifyBody(String binding, String otp) {
    return "{\"msisdn\":\"" + msisdn + "\",\"purpose\":\"CHECKOUT\",\"bindingId\":\"" + binding + "\",\"otp\":\""
        + otp + "\"}";
  }

  MvcTestResult post(String client, String path, String body) {
    return mvc.post().uri(path).contentType(MediaType.APPLICATION_JSON).content(body)
        .header("Authorization", InternalTokens.bearer(client)).exchange();
  }

  static void assertCode(MvcTestResult r, int status, String code) {
    assertThat(r).hasStatus(status);
    assertThat(r).bodyJson().extractingPath("$.code").isEqualTo(code);
  }

  /** A device-binding OTP of the wallet for {@code deviceId}, read from the SMS request. */
  String sendBindOtp(String deviceId) {
    clearInvocations(publisher);
    deviceOtps.send(msisdn, deviceId, "en");
    ArgumentCaptor<Object> sms = ArgumentCaptor.forClass(Object.class);
    verify(publisher).publish(eq("mw.notify.request.v1"), eq(msisdn), any(), sms.capture());
    return ((NotificationRequest) sms.getValue()).params().get("otp");
  }

  /** Sends one OTP and returns it, read from the SMS request. */
  String sendOtp() {
    clearInvocations(publisher);
    MvcTestResult r = post("mw-checkout", SEND, sendBody());
    assertThat(r).hasStatus(202);
    ArgumentCaptor<Object> sms = ArgumentCaptor.forClass(Object.class);
    verify(publisher).publish(eq("mw.notify.request.v1"), eq(msisdn), any(), sms.capture());
    NotificationRequest n = (NotificationRequest) sms.getValue();
    return n.params().get("otp");
  }

  @Test
  void aCheckoutOtpGoesBySmsWithTheCheckoutTemplate() throws Exception {
    MvcTestResult r = post("mw-checkout", SEND, sendBody());
    assertThat(r).hasStatus(202);
    assertThat(r).bodyJson().extractingPath("$.wallet").isEqualTo("0171****" + msisdn.substring(8));
    assertThat(r).bodyJson().extractingPath("$.sendsLeft").isEqualTo(4);
    ArgumentCaptor<Object> sms = ArgumentCaptor.forClass(Object.class);
    verify(publisher).publish(eq("mw.notify.request.v1"), eq(msisdn), any(), sms.capture());
    NotificationRequest n = (NotificationRequest) sms.getValue();
    assertThat(n.templateCode()).isEqualTo("CHECKOUT_OTP");
    assertThat(n.locale()).isEqualTo("en");
    assertThat(n.params()).containsEntry("amount", "250.00").containsEntry("merchant", "Demo Shop")
        .containsKey("otp");
    assertThat(n.priority()).isEqualTo("OTP");
    assertThat(jdbc.queryForObject(
            "select count(*) from channel_otp where wallet_id = ? and purpose = 'CHECKOUT' and binding_id = ?",
            Integer.class, msisdn, sessionId)).isEqualTo(1);
    assertThat(jdbc.queryForObject("select count(*) from device_otp where wallet_id = ?", Integer.class, msisdn))
        .isZero();
  }

  @Test
  void theRightOtpForTheSessionIsSpentOnce() {
    String otp = sendOtp();
    assertThat(post("mw-checkout", VERIFY, verifyBody(sessionId, otp))).hasStatus(204);
    assertCode(post("mw-checkout", VERIFY, verifyBody(sessionId, otp)), 401, "MW-AUTH-011");
  }

  @Test
  void anOtpOfAnotherSessionOrPurposeIsRefused() {
    String otp = sendOtp();
    assertCode(post("mw-checkout", VERIFY, verifyBody(UUID.randomUUID().toString(), otp)), 401, "MW-AUTH-011");
    // a device-binding OTP, even one sent for a device id equal to the session id, is not a checkout OTP
    String other = UUID.randomUUID().toString();
    String bindOtp = sendBindOtp(other);
    assertCode(post("mw-checkout", VERIFY, verifyBody(other, bindOtp)), 401, "MW-AUTH-011");
    // and the session's own OTP still works
    assertThat(post("mw-checkout", VERIFY, verifyBody(sessionId, otp))).hasStatus(204);
  }

  @Test
  void aCheckoutOtpNeverReplacesAPendingDeviceOtpNorTheOtherWayRound() {
    String device = "device-" + UUID.randomUUID();
    String bindOtp = sendBindOtp(device);
    String checkoutOtp = sendOtp();
    // the BIND OTP survives the checkout OTP ...
    deviceOtps.check(msisdn, device, bindOtp);
    // ... and a new BIND OTP leaves the checkout OTP alone
    sendBindOtp(device);
    assertThat(post("mw-checkout", VERIFY, verifyBody(sessionId, checkoutOtp))).hasStatus(204);
  }

  @Test
  void twoCheckoutSessionsHoldTheirOwnOtps() {
    String first = sendOtp();
    String firstSession = sessionId;
    sessionId = UUID.randomUUID().toString();
    String second = sendOtp();
    assertThat(post("mw-checkout", VERIFY, verifyBody(firstSession, first))).hasStatus(204);
    assertThat(post("mw-checkout", VERIFY, verifyBody(sessionId, second))).hasStatus(204);
  }

  @Test
  void threeWrongOtpsUseUpItsChecks() {
    String otp = sendOtp();
    String wrong = otp.equals("000000") ? "111111" : "000000";
    assertCode(post("mw-checkout", VERIFY, verifyBody(sessionId, wrong)), 401, "MW-AUTH-010");
    assertCode(post("mw-checkout", VERIFY, verifyBody(sessionId, wrong)), 401, "MW-AUTH-010");
    assertCode(post("mw-checkout", VERIFY, verifyBody(sessionId, wrong)), 401, "MW-AUTH-010");
    assertCode(post("mw-checkout", VERIFY, verifyBody(sessionId, otp)), 401, "MW-AUTH-012");
  }

  @Test
  void onlyMwCheckoutMayAsk() {
    assertCode(post("mw-app-gateway", SEND, sendBody()), 403, "MW-AUTH-002");
    assertCode(post("mw-ussd", VERIFY, verifyBody(sessionId, "123456")), 403, "MW-AUTH-002");
  }

  @Test
  void anUnknownWalletIsRefusedAndNothingIsSent() {
    msisdn = "01999999999";
    assertCode(post("mw-checkout", SEND, sendBody()), 403, "MW-AUTH-002");
    verify(publisher, org.mockito.Mockito.never()).publish(anyString(), anyString(), any(), any());
  }

  @Test
  void anotherPurposeOrABadBindingIsAValidationError() {
    assertCode(post("mw-checkout", SEND, sendBody().replace("\"CHECKOUT\"", "\"BIND\"")), 400, "MW-VAL-001");
    assertCode(post("mw-checkout", SEND, sendBody().replace(sessionId, "x")), 400, "MW-VAL-001");
  }

  @Test
  void checkoutOtpsHaveTheirOwnSendQuotaPerWallet() {
    // device OTP sends do not use up the checkout quota ...
    for (int i = 0; i < 3; i++) {
      sendBindOtp("device-" + i + "-" + UUID.randomUUID());
    }
    // ... the quota counts every checkout session of the wallet (mw.auth.channel-otp.max-sends 5) ...
    for (int i = 0; i < 5; i++) {
      sessionId = UUID.randomUUID().toString();
      assertThat(post("mw-checkout", SEND, sendBody())).hasStatus(202);
    }
    assertCode(post("mw-checkout", SEND, sendBody()), 429, "MW-VAL-022");
    // ... and an exhausted checkout quota does not stop a device OTP
    sendBindOtp("device-after-" + UUID.randomUUID());
  }

  @Test
  void kafkaRefusingTheSmsIs503() {
    doThrow(new IllegalStateException("kafka down")).when(publisher).publish(anyString(), anyString(), any(), any());
    assertCode(post("mw-checkout", SEND, sendBody()), 503, "MW-SYS-010");
  }
}
