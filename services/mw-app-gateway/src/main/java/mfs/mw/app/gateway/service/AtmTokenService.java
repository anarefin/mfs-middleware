package mfs.mw.app.gateway.service;

import mfs.mw.app.gateway.client.AuthClient;
import mfs.mw.app.gateway.config.AppGatewayProperties;
import mfs.mw.app.gateway.dto.AppUser;
import mfs.mw.app.gateway.dto.AtmTokenIssue;
import mfs.mw.app.gateway.dto.AtmTokenRequest;
import mfs.mw.app.gateway.dto.AtmTokenView;
import mfs.mw.app.gateway.dto.TxnAuthorisationRequest;
import mfs.mw.app.gateway.exception.ErrorCodes;
import mfs.mw.app.gateway.exception.MwException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * The ATM cash-out token (ticket 120b2, FRS FL-11, UC-17): {@code POST /app/v1/atm/tokens}. A
 * customer wallet only ({@code mw.app-gateway.txn-type-roles.ATM_CASH_OUT}). The PIN step-up is
 * mw-auth's transaction assertion for {@code ATM_CASH_OUT} wallet -> {@code PARTNER/<partnerId>}
 * with the token's amount and the request's idempotency key; mw-auth then issues the token against
 * that assertion and spends it (one PIN entry, one token). A wrong PIN, a PIN lock or a refused
 * step-up is mw-auth's answer as it is. The token is mw-auth's: 6 digits, 10 min, single use, bound
 * to wallet + amount, wrong tokens locked like OTPs; it is passed to the app once, never stored or
 * logged here.
 */
@Service
public class AtmTokenService {

  static final String TXN_TYPE = "ATM_CASH_OUT";
  private static final Logger log = LoggerFactory.getLogger(AtmTokenService.class);

  private final AuthClient auth;
  private final AppGatewayProperties props;
  private final String partnerId;

  public AtmTokenService(
      AuthClient auth,
      AppGatewayProperties props,
      @Value("${mw.app-gateway.atm.partner-id:ucbatm}") String partnerId) {
    this.auth = auth;
    this.props = props;
    this.partnerId = partnerId;
  }

  public AtmTokenView issue(AppUser user, String idempotencyKey, AtmTokenRequest body) {
    if (!props.mayInitiate(user.walletType(), TXN_TYPE)) {
      throw new MwException(ErrorCodes.AUTH_FORBIDDEN, "This app role cannot make an ATM token")
          .withStatus(HttpStatus.FORBIDDEN);
    }
    long amount = body.amount().valueMinor();
    String currency = body.amount().currency();
    String assertion = auth.authorise(new TxnAuthorisationRequest(user.msisdn(), user.channel(), user.deviceId(),
        body.encryptedPin(), new TxnAuthorisationRequest.Txn(TXN_TYPE, amount, currency,
            new TxnAuthorisationRequest.TxnParty("WALLET", user.msisdn()),
            new TxnAuthorisationRequest.TxnParty("PARTNER", partnerId), idempotencyKey))).assertion();
    AtmTokenView token = auth.issueAtmToken(new AtmTokenIssue(
        user.msisdn(), user.channel(), user.deviceId(), amount, currency, idempotencyKey, assertion));
    log.atInfo().addKeyValue("event", "atm.token.issued").addKeyValue("channel", user.channel())
        .log("ATM token issued");
    return token.withAmount(body.amount());
  }
}
