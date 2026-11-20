package mfs.mw.app.gateway.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import mfs.mw.app.gateway.client.ComplianceClient;
import mfs.mw.app.gateway.client.ComplianceClient.Subject;
import mfs.mw.app.gateway.config.ComplianceClientProperties;
import mfs.mw.app.gateway.config.SecurityConfig;
import mfs.mw.app.gateway.exception.MwException;
import mfs.mw.app.gateway.security.AppUserResolver;
import mfs.mw.app.gateway.service.OnboardingService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.json.JsonMapper;

/**
 * eKYC onboarding (ticket 124b, FRS UC-01): the Customer app applies for its own wallet, the DSO and Agent apps
 * assist with the agent id from the token; the gateway names the end user to mw-compliance in the subject headers
 * (security review of 124a) and checks the image type and size before forwarding.
 */
@WebMvcTest(OnboardingController.class)
@Import({SecurityConfig.class, AppUserResolver.class, OnboardingService.class})
@EnableConfigurationProperties(ComplianceClientProperties.class)
class OnboardingControllerTest {

  static final String CUSTOMER = "01711000001";
  static final String DSO = "01811000017";
  static final String ID = "11111111-1111-4111-8111-111111111111";
  static final JsonMapper JSON = JsonMapper.builder().build();
  static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 1, 2, 3};

  private final MockMvcTester mvc;

  @MockitoBean ComplianceClient compliance;

  @Autowired
  OnboardingControllerTest(MockMvcTester mvc) {
    this.mvc = mvc;
  }

  static RequestPostProcessor token(String msisdn, String role) {
    return jwt().jwt(j -> j.claim("msisdn", msisdn).claim("role", role).claim("amr", List.of("pin")));
  }

  static String application(String walletId) {
    String wallet = walletId == null ? "" : "\"walletId\":\"" + walletId + "\",";
    return "{" + wallet + "\"nid\":\"1990123456789\",\"dob\":\"1990-05-17\",\"nameEn\":\"Rahim Uddin\","
        + "\"consent\":{\"purpose\":\"EKYC_ONBOARDING\"}}";
  }

  static String document(String type, byte[] content) {
    return "{\"type\":\"SELFIE\",\"contentType\":\"" + type + "\",\"content\":\""
        + Base64.getEncoder().encodeToString(content) + "\"}";
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> createdBody(Subject subject) {
    ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
    verify(compliance).post(eq("/internal/v1/kyc/applications"), body.capture(), eq(subject));
    return JSON.convertValue(body.getValue(), Map.class);
  }

  @Test
  void theCustomerAppliesForItsOwnWalletOnTheAppChannel() {
    when(compliance.post(anyString(), any(), any())).thenReturn(JSON.readTree("{\"applicationId\":\"" + ID + "\"}"));

    MvcTestResult res = mvc.post().uri("/app/v1/onboarding/applications").with(token(CUSTOMER, "customer"))
        .contentType(MediaType.APPLICATION_JSON).content(application(null)).exchange();

    assertThat(res).hasStatus(201).bodyJson().extractingPath("$.applicationId").isEqualTo(ID);
    Map<String, Object> sent = createdBody(new Subject("WALLET", CUSTOMER));
    assertThat(sent).containsEntry("walletId", CUSTOMER).containsEntry("channel", "APP").doesNotContainKey("agentId");
  }

  @Test
  void aCustomerNamingAnotherWalletIsRefused() {
    assertThat(mvc.post().uri("/app/v1/onboarding/applications").with(token(CUSTOMER, "customer"))
        .contentType(MediaType.APPLICATION_JSON).content(application("01711000099"))).hasStatus(403);
    verify(compliance, never()).post(anyString(), any(), any());
  }

  @Test
  void theDsoAssistsAsItselfWithTheAgentIdFromTheToken() {
    when(compliance.post(anyString(), any(), any())).thenReturn(JSON.readTree("{\"applicationId\":\"" + ID + "\"}"));

    assertThat(mvc.post().uri("/app/v1/onboarding/applications").with(token(DSO, "dso"))
        .contentType(MediaType.APPLICATION_JSON).content(application(CUSTOMER))).hasStatus(201);

    Map<String, Object> sent = createdBody(new Subject("AGENT", DSO));
    assertThat(sent).containsEntry("walletId", CUSTOMER).containsEntry("channel", "DSO").containsEntry("agentId", DSO);
  }

  @Test
  void theAgentAppAssistsOnTheAgentChannelAndNeedsTheCustomersWallet() {
    when(compliance.post(anyString(), any(), any())).thenReturn(JSON.readTree("{\"applicationId\":\"" + ID + "\"}"));
    assertThat(mvc.post().uri("/app/v1/onboarding/applications").with(token(DSO, "agent"))
        .contentType(MediaType.APPLICATION_JSON).content(application(CUSTOMER))).hasStatus(201);
    assertThat(createdBody(new Subject("AGENT", DSO))).containsEntry("channel", "AGENT");

    assertThat(mvc.post().uri("/app/v1/onboarding/applications").with(token(DSO, "agent"))
        .contentType(MediaType.APPLICATION_JSON).content(application(null))).hasStatus(400);
  }

  @Test
  void anotherAppRoleMayNotOnboard() {
    assertThat(mvc.post().uri("/app/v1/onboarding/applications").with(token(DSO, "merchant"))
        .contentType(MediaType.APPLICATION_JSON).content(application(CUSTOMER))).hasStatus(403);
    verify(compliance, never()).post(anyString(), any(), any());
  }

  @Test
  void anImageIsForwardedUnderTheSubject() {
    when(compliance.post(anyString(), any(), any())).thenReturn(JSON.readTree("{\"documentId\":\"d-1\"}"));

    assertThat(mvc.post().uri("/app/v1/onboarding/applications/" + ID + "/documents").with(token(CUSTOMER, "customer"))
        .contentType(MediaType.APPLICATION_JSON).content(document("image/jpeg", JPEG))).hasStatus(201);
    verify(compliance).post(eq("/internal/v1/kyc/applications/" + ID + "/documents"), any(),
        eq(new Subject("WALLET", CUSTOMER)));
  }

  @Test
  void anImageOfAnotherTypeOrOver2MbNeverLeavesTheGateway() {
    assertThat(mvc.post().uri("/app/v1/onboarding/applications/" + ID + "/documents").with(token(CUSTOMER, "customer"))
        .contentType(MediaType.APPLICATION_JSON).content(document("image/gif", JPEG))).hasStatus(400);
    assertThat(mvc.post().uri("/app/v1/onboarding/applications/" + ID + "/documents").with(token(CUSTOMER, "customer"))
        .contentType(MediaType.APPLICATION_JSON).content(document("image/jpeg", new byte[2 * 1024 * 1024 + 1])))
        .hasStatus(400);
    verify(compliance, never()).post(anyString(), any(), any());
  }

  @Test
  void submitAndReadGoUnderTheSubjectAndComplianceRefusalsPassThrough() {
    when(compliance.post(eq("/internal/v1/kyc/applications/" + ID + "/submit"), any(), eq(new Subject("AGENT", DSO))))
        .thenReturn(JSON.readTree("{\"applicationId\":\"" + ID + "\",\"status\":\"APPROVED\"}"));
    assertThat(mvc.post().uri("/app/v1/onboarding/applications/" + ID + "/submit").with(token(DSO, "dso")))
        .hasStatusOk().bodyJson().extractingPath("$.status").isEqualTo("APPROVED");

    when(compliance.get("/internal/v1/kyc/applications/" + ID, new Subject("WALLET", CUSTOMER)))
        .thenThrow(new MwException("MW-VAL-003", "No such application").withStatus(HttpStatus.NOT_FOUND));
    assertThat(mvc.get().uri("/app/v1/onboarding/applications/" + ID).with(token(CUSTOMER, "customer")))
        .hasStatus(404).bodyJson().extractingPath("$.code").isEqualTo("MW-VAL-003");
  }

  @Test
  void noTokenIs401() {
    assertThat(mvc.get().uri("/app/v1/onboarding/applications/" + ID)).hasStatus(401);
  }
}
