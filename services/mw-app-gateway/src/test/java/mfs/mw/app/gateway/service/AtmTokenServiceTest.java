package mfs.mw.app.gateway.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import mfs.mw.app.gateway.config.AppGatewayProperties;
import mfs.mw.app.gateway.client.AuthClient;
import mfs.mw.app.gateway.dto.Amount;
import mfs.mw.app.gateway.dto.AppUser;
import mfs.mw.app.gateway.dto.AtmTokenIssue;
import mfs.mw.app.gateway.dto.AtmTokenRequest;
import mfs.mw.app.gateway.dto.AtmTokenView;
import mfs.mw.app.gateway.dto.EncryptedPin;
import mfs.mw.app.gateway.dto.TxnAuthorisation;
import mfs.mw.app.gateway.dto.TxnAuthorisationRequest;
import mfs.mw.app.gateway.exception.MwException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.http.HttpStatus;

/**
 * ATM token issue in the gateway (ticket 120b2): the PIN step-up is mw-auth's assertion for {@code
 * ATM_CASH_OUT} wallet -> {@code PARTNER/ucbatm} with the token's amount and the request's key, then
 * mw-auth issues the token against it. mw-auth is the boundary (mocked).
 */
class AtmTokenServiceTest {

  static final String MSISDN = "01711000001";
  static final EncryptedPin PIN = new EncryptedPin("pin-transport.v1", "Y2lwaGVy");
  static final AppUser CUSTOMER = new AppUser(MSISDN, "CUSTOMER_APP", "CUSTOMER", "dev-1");
  static final Instant EXP = Instant.parse("2026-11-20T12:10:00Z");

  final AuthClient auth = mock(AuthClient.class);
  final AtmTokenService service = new AtmTokenService(auth,
      new AppGatewayProperties(
          Duration.ofMillis(200), "gw-unit", "mw.txn.request.v1", "mw.txn.status.v1",
          List.of("P2P"), List.of("reference"),
          new AppGatewayProperties.Jwt("http://mw-auth/jwks", "mw-auth", "mw-app"),
          Map.of("ATM_CASH_OUT", List.of("CUSTOMER"))),
      "ucbatm");

  @Test
  void theStepUpAssertionIsForTheTokenTxnAndTheTokenIsIssuedAgainstIt() {
    when(auth.authorise(any())).thenReturn(new TxnAuthorisation("jws.a", EXP, false));
    when(auth.issueAtmToken(any())).thenReturn(new AtmTokenView("123456", EXP, null));

    AtmTokenView v = service.issue(CUSTOMER, "key-1", new AtmTokenRequest(new Amount(200_000L, "BDT"), PIN));

    assertThat(v).isEqualTo(new AtmTokenView("123456", EXP, new Amount(200_000L, "BDT")));
    ArgumentCaptor<TxnAuthorisationRequest> a = ArgumentCaptor.forClass(TxnAuthorisationRequest.class);
    ArgumentCaptor<AtmTokenIssue> i = ArgumentCaptor.forClass(AtmTokenIssue.class);
    InOrder order = inOrder(auth);
    order.verify(auth).authorise(a.capture());
    order.verify(auth).issueAtmToken(i.capture());
    assertThat(a.getValue()).isEqualTo(new TxnAuthorisationRequest(MSISDN, "CUSTOMER_APP", "dev-1", PIN,
        new TxnAuthorisationRequest.Txn("ATM_CASH_OUT", 200_000L, "BDT",
            new TxnAuthorisationRequest.TxnParty("WALLET", MSISDN),
            new TxnAuthorisationRequest.TxnParty("PARTNER", "ucbatm"), "key-1")));
    assertThat(i.getValue())
        .isEqualTo(new AtmTokenIssue(MSISDN, "CUSTOMER_APP", "dev-1", 200_000L, "BDT", "key-1", "jws.a"));
  }

  @Test
  void aWrongPinIsMwAuthsAnswerAndNoTokenIsAsked() {
    when(auth.authorise(any()))
        .thenThrow(new MwException("MW-AUTH-040", "Wrong PIN").withStatus(HttpStatus.UNAUTHORIZED));

    MwException e = catchThrowableOfType(MwException.class,
        () -> service.issue(CUSTOMER, "key-2", new AtmTokenRequest(new Amount(200_000L, "BDT"), PIN)));

    assertThat(e.code()).isEqualTo("MW-AUTH-040");
    verify(auth, never()).issueAtmToken(any());
  }

  @Test
  void onlyACustomerWalletGetsAToken() {
    for (AppUser other : new AppUser[] {
        new AppUser(MSISDN, "AGENT_APP", "AGENT", "dev-1"), new AppUser(MSISDN, "MERCHANT_APP", "MERCHANT", "dev-1")}) {
      MwException e = catchThrowableOfType(MwException.class,
          () -> service.issue(other, "key-3", new AtmTokenRequest(new Amount(200_000L, "BDT"), PIN)));
      assertThat(e.code()).isEqualTo("MW-AUTH-002");
    }
    verifyNoInteractions(auth);
  }
}
