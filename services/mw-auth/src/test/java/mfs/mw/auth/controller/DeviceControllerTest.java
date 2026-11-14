package mfs.mw.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import mfs.mw.auth.config.DeviceBindingConfig;
import mfs.mw.auth.config.PublicConnectorConfig;
import mfs.mw.auth.config.SecurityConfig;
import mfs.mw.auth.dto.DeviceBound;
import mfs.mw.auth.dto.DeviceOtpSent;
import mfs.mw.auth.dto.TokenPair;
import mfs.mw.auth.service.DeviceBindingService;
import mfs.mw.auth.service.JwksService;
import mfs.mw.auth.service.LoginService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * The public {@code /auth/v1} bodies (OTP, binding, login, refresh): the contract-derived body schema
 * is checked on the cached bytes (the ones a device signature covers; APISIX validates no body, M3
 * gate decision A6) before the service runs; failures are 400 MW-VAL-001. No bearer token is needed
 * (ticket 69: the wallet is named by its MSISDN, a stale bearer is ignored). The services are mocks
 * here; {@code DeviceBindingIT} and {@code LoginIT} run them against PostgreSQL and Valkey.
 */
@WebMvcTest({DeviceController.class, LoginController.class})
@Import({SecurityConfig.class, PublicConnectorConfig.class, DeviceBindingConfig.class})
class DeviceControllerTest {

  static final String MSISDN = "01711000001";
  static final String PIN = "{\"keyId\":\"pin-transport.v1\",\"ciphertext\":\"AAAA\"}";
  static final String BIND =
      "{\"msisdn\":\"" + MSISDN + "\",\"publicKey\":\"MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE\",\"otp\":\"123456\"}";

  private final MockMvcTester mvc;

  @MockitoBean DeviceBindingService devices;
  @MockitoBean LoginService logins;
  @MockitoBean JwksService jwks;

  @Autowired
  DeviceControllerTest(MockMvcTester mvc) {
    this.mvc = mvc;
  }

  MvcTestResult post(String path, String body) {
    var req = mvc.post().uri(path).header("X-Device-Id", "dev-00000001");
    return (body == null ? req : req.contentType(MediaType.APPLICATION_JSON).content(body)).exchange();
  }

  static TokenPair pair() {
    return new TokenPair("a.b.c", "Bearer", 300, "r".repeat(43), 2592000, "sid", 300, true);
  }

  @Test
  void aValidOtpRequestReachesTheServiceWithoutAToken() {
    when(devices.sendOtp(eq(MSISDN), eq("dev-00000001"), isNull(), eq("en")))
        .thenReturn(new DeviceOtpSent("0171****001", Instant.parse("2026-11-05T10:15:00Z"), 4));

    assertThat(post("/auth/v1/otp", "{\"msisdn\":\"" + MSISDN + "\",\"locale\":\"en\"}")).hasStatus(202);
    verify(devices).sendOtp(eq(MSISDN), eq("dev-00000001"), isNull(), eq("en"));
  }

  @Test
  void aStaleBearerTokenOnAnOpenEndpointIsIgnored() {
    when(devices.sendOtp(any(), any(), any(), any()))
        .thenReturn(new DeviceOtpSent("0171****001", Instant.parse("2026-11-05T10:15:00Z"), 4));
    var r = mvc.post().uri("/auth/v1/otp").header("X-Device-Id", "dev-00000001")
        .header("Authorization", "Bearer not.a.token").contentType(MediaType.APPLICATION_JSON)
        .content("{\"msisdn\":\"" + MSISDN + "\",\"purpose\":\"LOGIN\"}").exchange();
    assertThat(r).hasStatus(202);
  }

  @Test
  void anOtpRequestBreakingTheSchemaIsRefusedBeforeTheService() {
    for (String body : new String[] {
        null, "{\"locale\":\"en\"}", "{\"msisdn\":\"" + MSISDN + "\",\"locale\":\"fr\"}",
        "{\"msisdn\":\"" + MSISDN + "\",\"purpose\":\"CHECKOUT\"}", "{\"msisdn\":\"" + MSISDN + "\",\"lang\":\"en\"}",
        "{\"msisdn\":\"12345\"}", "[]", "{} {}", "{"}) {
      assertThat(post("/auth/v1/otp", body)).as(body).hasStatus(400)
          .bodyJson().extractingPath("$.code").isEqualTo("MW-VAL-001");
    }
    verify(devices, never()).sendOtp(any(), any(), any(), any());
  }

  @Test
  void aValidBindingBodyReachesTheService() {
    when(devices.bind(eq(MSISDN), any(), eq("123456"), any(), any()))
        .thenReturn(new DeviceBound("dev-00000001", "0171****001", Instant.parse("2026-11-05T10:15:00Z")));

    assertThat(post("/auth/v1/devices", BIND)).hasStatus(201);
    assertThat(post("/auth/v1/devices", BIND.replace("}", ",\"encryptedPin\":" + PIN + "}"))).hasStatus(201);
  }

  @Test
  void aBindingBodyBreakingTheSchemaIsRefusedBeforeTheService() {
    for (String body : new String[] {
        "{\"publicKey\":\"MFkw\",\"otp\":\"123456\"}",
        BIND.replace("\"otp\":\"123456\"", "\"otp\":123456"),
        BIND.replace("}", ",\"extra\":1}"),
        BIND.replace("\"otp\":\"123456\"", "\"otp\":\"123456\",\"otp\":\"654321\""),
        BIND.replace("MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE", "A".repeat(513)),
        BIND.replace("}", ",\"encryptedPin\":{\"keyId\":\"k\"}}"),
        BIND + "x",
    }) {
      assertThat(post("/auth/v1/devices", body)).as(body).hasStatus(400)
          .bodyJson().extractingPath("$.code").isEqualTo("MW-VAL-001");
    }
    verify(devices, never()).bind(any(), any(), any(), any(), any());
  }

  @Test
  void aLoginTakesThePinOrAnOtpButNotBoth() {
    when(logins.login(any(), any(), any(), any())).thenReturn(pair());
    assertThat(post("/auth/v1/sessions", "{\"msisdn\":\"" + MSISDN + "\",\"encryptedPin\":" + PIN + "}"))
        .hasStatus(200).bodyJson().extractingPath("$.tokenType").isEqualTo("Bearer");
    assertThat(post("/auth/v1/sessions", "{\"msisdn\":\"" + MSISDN + "\",\"otp\":\"123456\"}")).hasStatus(200);

    for (String body : new String[] {
        "{\"msisdn\":\"" + MSISDN + "\"}",
        "{\"msisdn\":\"" + MSISDN + "\",\"otp\":\"123456\",\"encryptedPin\":" + PIN + "}",
        "{\"msisdn\":\"" + MSISDN + "\",\"pin\":\"12345\"}",
        "{\"encryptedPin\":" + PIN + "}",
        "{\"msisdn\":\"" + MSISDN + "\",\"encryptedPin\":{\"keyId\":\"pin-transport.v1\",\"ciphertext\":\"not base64!\"}}",
    }) {
      assertThat(post("/auth/v1/sessions", body)).as(body).hasStatus(400)
          .bodyJson().extractingPath("$.code").isEqualTo("MW-VAL-001");
    }
  }

  @Test
  void aRefreshBodyIsChecked() {
    when(logins.refresh(any(), any())).thenReturn(pair());
    assertThat(post("/auth/v1/tokens/refresh", "{\"refreshToken\":\"" + "A".repeat(43) + "\"}")).hasStatus(200);
    assertThat(post("/auth/v1/tokens/refresh", "{\"refreshToken\":\"short\"}")).hasStatus(400);
    assertThat(post("/auth/v1/tokens/refresh", "{}")).hasStatus(400);
  }

  @Test
  void logoutAndTheFirstPinNeedAToken() {
    assertThat(post("/auth/v1/sessions/logout", null)).hasStatus(401)
        .bodyJson().extractingPath("$.code").isEqualTo("MW-AUTH-001");
    assertThat(post("/auth/v1/pin", "{\"encryptedPin\":" + PIN + "}")).hasStatus(401);
  }
}
