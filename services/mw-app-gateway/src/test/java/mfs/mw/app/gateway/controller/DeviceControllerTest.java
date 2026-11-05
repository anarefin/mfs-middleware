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
import mfs.mw.app.gateway.dto.DeviceOtpSent;
import mfs.mw.app.gateway.dto.DeviceSession;
import mfs.mw.app.gateway.security.AppUserResolver;
import mfs.mw.app.gateway.service.DeviceBindingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * {@code POST /app/v1/devices/otp} and {@code POST /app/v1/devices}: the contract-derived body
 * schema is checked on the cached bytes (the ones a device signature covers; APISIX validates no
 * body, M3 gate decision A6), before the service runs; failures are 400 MW-VAL-001. The device
 * gate is off in this slice ({@code device-binding.enabled=false}); {@code DeviceBindingIT} runs it.
 */
@WebMvcTest(DeviceController.class)
@Import({SecurityConfig.class, AppUserResolver.class})
class DeviceControllerTest {

  static final String BIND = "{\"publicKey\":\"MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE\",\"otp\":\"123456\"}";

  private final MockMvcTester mvc;

  @MockitoBean DeviceBindingService devices;

  @Autowired
  DeviceControllerTest(MockMvcTester mvc) {
    this.mvc = mvc;
  }

  static RequestPostProcessor customer() {
    return jwt()
        .jwt(j -> j.claim("msisdn", "01711000001").claim("realm_access", Map.of("roles", List.of("customer"))));
  }

  MvcTestResult post(String path, String body) {
    var req = mvc.post().uri(path).with(customer()).header("X-Device-Id", "dev-1");
    return (body == null ? req : req.contentType(MediaType.APPLICATION_JSON).content(body)).exchange();
  }

  @Test
  void theOtpRequestBodyIsOptional() {
    when(devices.sendOtp(any(), eq("dev-1"), isNull()))
        .thenReturn(new DeviceOtpSent("0171****001", Instant.parse("2026-11-05T10:15:00Z"), 4));

    assertThat(post("/app/v1/devices/otp", null)).hasStatus(202);
    verify(devices).sendOtp(any(), eq("dev-1"), isNull());
  }

  @Test
  void aValidOtpRequestBodyReachesTheService() {
    when(devices.sendOtp(any(), eq("dev-1"), eq("en")))
        .thenReturn(new DeviceOtpSent("0171****001", Instant.parse("2026-11-05T10:15:00Z"), 4));

    assertThat(post("/app/v1/devices/otp", "{\"locale\":\"en\"}")).hasStatus(202);
    verify(devices).sendOtp(any(), eq("dev-1"), eq("en"));
  }

  @Test
  void anOtpRequestBodyBreakingTheSchemaIsRefusedBeforeTheService() {
    for (String body :
        new String[] {"{\"locale\":\"fr\"}", "{\"lang\":\"en\"}", "{\"locale\":\"en\",\"locale\":\"bn\"}", "[]", "{} {}", "{"}) {
      assertThat(post("/app/v1/devices/otp", body)).as(body).hasStatus(400)
          .bodyJson().extractingPath("$.code").isEqualTo("MW-VAL-001");
    }
    verify(devices, never()).sendOtp(any(), any(), any());
  }

  @Test
  void aValidBindingBodyReachesTheService() {
    when(devices.bind(any(), any(), any(), eq("123456"), any())).thenReturn(new DeviceSession("dev-1", 300));

    assertThat(post("/app/v1/devices", BIND)).hasStatus(201);
    verify(devices).bind(any(), any(), any(), eq("123456"), any());
  }

  @Test
  void aBindingBodyBreakingTheSchemaIsRefusedBeforeTheService() {
    for (String body :
        new String[] {
          "{\"publicKey\":\"MFkw\"}",
          BIND.replace("\"otp\":\"123456\"", "\"otp\":123456"),
          BIND.replace("}", ",\"extra\":1}"),
          BIND.replace("\"otp\":\"123456\"", "\"otp\":\"123456\",\"otp\":\"654321\""),
          BIND.replace("MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE", "A".repeat(513)),
          BIND + "x",
        }) {
      assertThat(post("/app/v1/devices", body)).as(body).hasStatus(400)
          .bodyJson().extractingPath("$.code").isEqualTo("MW-VAL-001");
    }
    verify(devices, never()).bind(any(), any(), any(), any(), any());
  }
}
