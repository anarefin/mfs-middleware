package mfs.mw.adapter.card.partner;

import java.util.regex.Pattern;

/**
 * Where a partner secret lives, never the secret itself (partner config holds only references,
 * spec 20.4). A partner record names a secret by a bare field name ({@code hmac-secret}), a field
 * of that partner's own Vault secret {@code secret/<service>/<partner>}, read when its route
 * loads ({@code partner/auth/PartnerCredentials}, ADR D28). So a partner can reach its own secrets
 * only: never another partner's, never any other secret of the service, whatever arrives on {@code
 * mw.config.changed.v1}.
 */
public record CredentialRef(String path) {

  /** A secret field name in partner config: lower-case letters, digits, hyphen; no dots or slashes. */
  public static final Pattern NAME = Pattern.compile("^[a-z0-9][a-z0-9-]{0,63}$");

  public static final CredentialRef NONE = new CredentialRef(null);

  public boolean isNone() {
    return path == null || path.isBlank();
  }
}
