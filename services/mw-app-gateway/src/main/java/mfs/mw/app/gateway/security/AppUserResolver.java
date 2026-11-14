package mfs.mw.app.gateway.security;

import java.util.Collection;
import mfs.mw.app.gateway.dto.AppUser;
import mfs.mw.app.gateway.exception.ErrorCodes;
import mfs.mw.app.gateway.exception.MwException;
import mfs.mw.app.gateway.util.Msisdn;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Turns a validated mw-auth access token (ticket 69, spec 18.7, D21) into the {@link AppUser}: the
 * wallet is the {@code msisdn} claim, the app follows the {@code role} claim (from the wallet type):
 * {@code customer} is the customer app, {@code agent} the agent app, {@code merchant} the merchant
 * app (spec WP20; which role may initiate which {@code txnType} is checked at submit, the merchant
 * collections view by {@code CollectionService}); any other role (distributor, dso: no app here yet)
 * is 403 MW-AUTH-002. Only a PIN session may use the app API: a token whose {@code amr} has no {@code
 * pin} (a first-PIN OTP login) is 403 MW-AUTH-045 until the PIN is set at mw-auth.
 */
@Component
public class AppUserResolver {

  static final String MSISDN_CLAIM = "msisdn";
  static final String ROLE_CLAIM = "role";
  static final String AMR_CLAIM = "amr";
  /** The token's device ({@code deviceId} claim); the device gate compares it with {@code X-Device-Id}. */
  public static final String DEVICE_CLAIM = "deviceId";

  public AppUser resolve(Jwt jwt) {
    String msisdn = Msisdn.canonical(jwt.getClaimAsString(MSISDN_CLAIM));
    if (msisdn == null) {
      throw forbidden("Token carries no wallet");
    }
    Object amr = jwt.getClaims().get(AMR_CLAIM);
    if (!(amr instanceof Collection<?> methods) || !methods.contains("pin")) {
      throw new MwException(ErrorCodes.AUTH_PIN_NOT_SET, "Set your PIN first").withStatus(HttpStatus.FORBIDDEN);
    }
    String role = jwt.getClaimAsString(ROLE_CLAIM);
    String device = jwt.getClaimAsString(DEVICE_CLAIM);
    if ("customer".equals(role)) {
      return new AppUser(msisdn, "CUSTOMER_APP", "CUSTOMER", device);
    }
    if ("agent".equals(role)) {
      return new AppUser(msisdn, "AGENT_APP", "AGENT", device);
    }
    if ("merchant".equals(role)) {
      return new AppUser(msisdn, "MERCHANT_APP", "MERCHANT", device);
    }
    throw forbidden("This app role cannot use this operation");
  }

  private static MwException forbidden(String message) {
    return new MwException(ErrorCodes.AUTH_FORBIDDEN, message).withStatus(HttpStatus.FORBIDDEN);
  }
}
