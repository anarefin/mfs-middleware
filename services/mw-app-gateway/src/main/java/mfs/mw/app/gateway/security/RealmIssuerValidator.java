package mfs.mw.app.gateway.security;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Accepts a token only when its issuer ends with the expected realm path (e.g. {@code
 * /realms/customers}). The scheme, host and port of {@code iss} follow the URL the client used to
 * reach Keycloak (directly or through APISIX), so they are not compared.
 */
public class RealmIssuerValidator implements OAuth2TokenValidator<Jwt> {

  private final String realmPath;

  public RealmIssuerValidator(String realmPath) {
    this.realmPath = realmPath;
  }

  @Override
  public OAuth2TokenValidatorResult validate(Jwt jwt) {
    String iss = jwt.getIssuer() == null ? null : jwt.getIssuer().toString();
    if (iss != null && (iss.endsWith(realmPath) || iss.endsWith(realmPath + "/"))) {
      return OAuth2TokenValidatorResult.success();
    }
    return OAuth2TokenValidatorResult.failure(
        new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN, "token is not from the expected realm", null));
  }
}
