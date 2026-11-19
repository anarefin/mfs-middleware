package mfs.mw.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import mfs.mw.auth.config.DeviceBindingConfig;
import mfs.mw.auth.config.PublicConnectorConfig;
import mfs.mw.auth.config.SecurityConfig;
import mfs.mw.auth.dto.EncryptedPin;
import mfs.mw.auth.dto.TestAccount;
import mfs.mw.auth.exception.ErrorCodes;
import mfs.mw.auth.exception.MwException;
import mfs.mw.auth.service.JwksService;
import mfs.mw.auth.service.TestAccountService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/**
 * The dev-only account test API ({@code auth-test.v1}) with {@code mw.auth.seed-api.enabled=true}: no
 * token needed, the wallet id checked, the PIN body validated, the service's errors as the envelope.
 * The service is a mock here; {@code security/TestAccountIT} runs it against PostgreSQL and Redis.
 */
@WebMvcTest(controllers = TestAccountController.class, properties = "mw.auth.seed-api.enabled=true")
@Import({SecurityConfig.class, PublicConnectorConfig.class, DeviceBindingConfig.class})
class TestAccountControllerTest {

  static final String WALLET = "01711000001";
  static final String BASE = "/test/v1/accounts/" + WALLET;
  static final TestAccount ACCOUNT = new TestAccount(WALLET, "ACTIVE", true, false, null, 0, null, false, null, 0,
      List.of(new TestAccount.Device("dev-00000001", "BOUND", Instant.parse("2026-11-15T06:00:00Z"), null)));

  private final MockMvcTester mvc;

  @MockitoBean TestAccountService accounts;
  @MockitoBean JwksService jwks;

  @Autowired
  TestAccountControllerTest(MockMvcTester mvc) {
    this.mvc = mvc;
  }

  @Test
  void theAccountIsShownWithoutAToken() {
    when(accounts.view(WALLET)).thenReturn(ACCOUNT);
    var r = mvc.get().uri(BASE).exchange();
    assertThat(r).hasStatus(200);
    assertThat(r).bodyJson().extractingPath("$.walletId").isEqualTo(WALLET);
    assertThat(r).bodyJson().extractingPath("$.devices[0].status").isEqualTo("BOUND");
    assertThat(r).bodyJson().doesNotHavePath("$.lockedUntil");
  }

  @Test
  void anUnknownWalletIsTheNotFoundEnvelope() {
    when(accounts.view("01700000000"))
        .thenThrow(new MwException(ErrorCodes.VAL_NOT_FOUND, "Unknown wallet").withStatus(HttpStatus.NOT_FOUND));
    var r = mvc.get().uri("/test/v1/accounts/01700000000").exchange();
    assertThat(r).hasStatus(404);
    assertThat(r).bodyJson().extractingPath("$.code").isEqualTo("MW-VAL-003");
  }

  @Test
  void aMalformedWalletIdIsRefusedBeforeTheService() {
    var r = mvc.get().uri("/test/v1/accounts/" + "x".repeat(65)).exchange();
    assertThat(r).hasStatus(400);
    assertThat(r).bodyJson().extractingPath("$.code").isEqualTo("MW-VAL-001");
    verify(accounts, never()).view(anyString());
  }

  @Test
  void unlockAndUnbindTakeNoBody() {
    when(accounts.unlock(WALLET)).thenReturn(ACCOUNT);
    when(accounts.unbind(WALLET)).thenReturn(ACCOUNT);
    assertThat(mvc.post().uri(BASE + "/unlock").exchange()).hasStatus(200);
    assertThat(mvc.post().uri(BASE + "/unbind").exchange()).hasStatus(200);
    verify(accounts).unlock(WALLET);
    verify(accounts).unbind(WALLET);
  }

  @Test
  void aPinIsPassedOnEncrypted() {
    when(accounts.setPin(eq(WALLET), any())).thenReturn(ACCOUNT);
    var r = mvc.post().uri(BASE + "/pin").contentType(MediaType.APPLICATION_JSON)
        .content("{\"encryptedPin\":{\"keyId\":\"pin-transport.v1\",\"ciphertext\":\"AAAA\"}}").exchange();
    assertThat(r).hasStatus(200);
    verify(accounts).setPin(WALLET, new EncryptedPin("pin-transport.v1", "AAAA"));
  }

  @Test
  void aPinBodyWithoutTheCiphertextIsRefused() {
    var r = mvc.post().uri(BASE + "/pin").contentType(MediaType.APPLICATION_JSON)
        .content("{\"encryptedPin\":{\"keyId\":\"pin-transport.v1\"}}").exchange();
    assertThat(r).hasStatus(400);
    assertThat(r).bodyJson().extractingPath("$.code").isEqualTo("MW-VAL-001");
    verify(accounts, never()).setPin(anyString(), any());
  }

  @Test
  void aWeakPinIsTheServicesEnvelope() {
    when(accounts.setPin(eq(WALLET), any())).thenThrow(
        new MwException(ErrorCodes.AUTH_WEAK_PIN, "Weak PIN").withStatus(HttpStatus.BAD_REQUEST));
    var r = mvc.post().uri(BASE + "/pin").contentType(MediaType.APPLICATION_JSON)
        .content("{\"encryptedPin\":{\"keyId\":\"pin-transport.v1\",\"ciphertext\":\"AAAA\"}}").exchange();
    assertThat(r).hasStatus(400);
    assertThat(r).bodyJson().extractingPath("$.code").isEqualTo("MW-AUTH-044");
  }
}
