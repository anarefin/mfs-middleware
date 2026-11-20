package mfs.mw.app.gateway.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import java.util.List;
import mfs.mw.app.gateway.config.SecurityConfig;
import mfs.mw.app.gateway.dto.AppUser;
import mfs.mw.app.gateway.dto.PushTokenRequest;
import mfs.mw.app.gateway.exception.MwException;
import mfs.mw.app.gateway.security.AppUserResolver;
import mfs.mw.app.gateway.service.PushTokenService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** {@code PUT /app/v1/notifications/push-token} (ticket 110): mapping, validation, the service's answers. */
@WebMvcTest(PushTokenController.class)
@Import({SecurityConfig.class, AppUserResolver.class})
class PushTokenControllerTest {

  static final String MSISDN = "01711000001";

  private final MockMvcTester mvc;

  @MockitoBean PushTokenService service;

  @Autowired
  PushTokenControllerTest(MockMvcTester mvc) {
    this.mvc = mvc;
  }

  MvcTestResult put(boolean auth, String body) {
    var req = mvc.put().uri("/app/v1/notifications/push-token").contentType(MediaType.APPLICATION_JSON).content(body)
        .header("X-Device-Id", "dev-0001");
    if (auth) {
      req = req.with(jwt().jwt(j -> j.claim("msisdn", MSISDN).claim("role", "customer").claim("amr", List.of("pin"))));
    }
    return req.exchange();
  }

  @Test
  void aTokenIsRegisteredForTheCallersWalletAndDevice() {
    MvcTestResult r = put(true, "{\"token\":\"fcm-test-token-abc123\",\"platform\":\"ANDROID\"}");

    assertThat(r).hasStatus(204);
    ArgumentCaptor<AppUser> user = ArgumentCaptor.forClass(AppUser.class);
    ArgumentCaptor<PushTokenRequest> body = ArgumentCaptor.forClass(PushTokenRequest.class);
    verify(service).register(user.capture(), eq("dev-0001"), body.capture());
    assertThat(user.getValue().msisdn()).isEqualTo(MSISDN);
    assertThat(body.getValue().token()).isEqualTo("fcm-test-token-abc123");
    assertThat(body.getValue().platform()).isEqualTo("ANDROID");
  }

  @Test
  void anUnknownPlatformOrABlankTokenIs400() {
    assertThat(put(true, "{\"token\":\"t\",\"platform\":\"WINDOWS\"}")).hasStatus(400);
    assertThat(put(true, "{\"token\":\" \",\"platform\":\"IOS\"}")).hasStatus(400);
    verifyNoInteractions(service);
  }

  @Test
  void withoutATokenIs401AndAKafkaOutageIs503() {
    assertThat(put(false, "{\"token\":\"t\",\"platform\":\"IOS\"}")).hasStatus(401);

    doThrow(new MwException("MW-SYS-002", "Service temporarily unavailable, try again")
            .withStatus(HttpStatus.SERVICE_UNAVAILABLE))
        .when(service).register(any(), any(), any());
    MvcTestResult r = put(true, "{\"token\":\"t\",\"platform\":\"IOS\"}");
    assertThat(r).hasStatus(503);
  }
}
