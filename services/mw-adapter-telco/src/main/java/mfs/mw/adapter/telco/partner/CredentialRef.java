package mfs.mw.adapter.telco.partner;

/**
 * Where a partner secret lives, never the secret itself (partner config holds only references,
 * spec 20.4). With Spring Cloud Vault the path is a property of {@code secret/<service>} in
 * OpenBao, e.g. {@code partners.gp.api-key}.
 */
public record CredentialRef(String path) {

  public static final CredentialRef NONE = new CredentialRef(null);

  public boolean isNone() {
    return path == null || path.isBlank();
  }
}
