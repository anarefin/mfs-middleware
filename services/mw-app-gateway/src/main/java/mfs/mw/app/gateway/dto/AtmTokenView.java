package mfs.mw.app.gateway.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import mfs.mw.app.gateway.util.MaskingPolicy;
import mfs.mw.app.gateway.util.Sensitive;
import mfs.mw.app.gateway.util.SensitiveType;

/**
 * The ATM token (ticket 120b2): mw-auth's answer {@code {token, expiresAt}} and the app's 201 with
 * the amount it is bound to. The token is shown once and never stored or logged here.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AtmTokenView(@Sensitive(SensitiveType.SECRET) String token, Instant expiresAt, Amount amount) {

  public AtmTokenView withAmount(Amount a) {
    return new AtmTokenView(token, expiresAt, a);
  }

  @Override
  public String toString() {
    return MaskingPolicy.describe(this);
  }
}
