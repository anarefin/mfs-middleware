package mfs.mw.adapter.billpay.partner;

import java.util.regex.Pattern;

/**
 * Where a partner secret lives, never the secret itself (partner config holds only references,
 * spec 20.4). A partner's config names a secret by a bare name ({@code api-key}); the framework
 * turns it into the property {@code mw.partner-secrets.<partner instance>.<name>}, which OpenBao
 * provides through Spring Cloud Vault ({@code secret/<service>}). So a partner can reach its own
 * secrets only: never another partner's, never any other property of the service (database
 * password, Vault token), whatever arrives on {@code mw.config.changed.v1}.
 */
public record CredentialRef(String path) {

  /** Every partner secret property starts with this. */
  public static final String PREFIX = "mw.partner-secrets.";

  /** A secret name in partner config: lower-case letters, digits, hyphen; no dots or slashes. */
  public static final Pattern NAME = Pattern.compile("^[a-z0-9][a-z0-9-]{0,63}$");

  public static final CredentialRef NONE = new CredentialRef(null);

  /** The ref of secret {@code name} of partner instance {@code instance} (both already checked). */
  public static CredentialRef of(String instance, String name) {
    return new CredentialRef(PREFIX + instance + "." + name);
  }

  public boolean isNone() {
    return path == null || path.isBlank();
  }
}
