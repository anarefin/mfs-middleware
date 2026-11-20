package mfs.mw.admin.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import mfs.mw.admin.api.util.Sensitive;
import mfs.mw.admin.api.util.SensitiveType;

/**
 * {@code POST /admin/v1/partners/{partnerId}/credentials/{name}/change-requests} (ticket 131):
 * upload a partner credential. Write-only: the secret parts are staged in Vault at once and never
 * stored in the change request, logged, audited or returned ({@link #toString()} redacts them). A
 * secret ({@link CredentialType#API_KEY}, {@link CredentialType#HMAC_SECRET}) comes as {@code
 * secret} with an optional {@code notAfter}; a client certificate as {@code certificatePem} +
 * {@code privateKeyPem} (PKCS#8) or as {@code pkcs12} (base64) + {@code pkcs12Password}.
 */
public record CredentialChangeBody(
    @NotNull CredentialAction action,
    @NotBlank @Size(max = 500) String reason,
    @NotNull CredentialType type,
    @Sensitive(SensitiveType.SECRET) @Size(max = 4096) String secret,
    @Size(max = 65536) String certificatePem,
    @Sensitive(SensitiveType.SECRET) @Size(max = 65536) String privateKeyPem,
    @Sensitive(SensitiveType.SECRET) @Size(max = 131072) String pkcs12,
    @Sensitive(SensitiveType.SECRET) @Size(max = 1024) String pkcs12Password,
    Instant notAfter) {

  /** The only credential change: a new value replaces the held one (rotation). */
  public enum CredentialAction {
    UPLOAD
  }

  @Override
  public String toString() {
    return "CredentialChangeBody[action=" + action + ", type=" + type + ", notAfter=" + notAfter + ", secrets=****]";
  }
}
