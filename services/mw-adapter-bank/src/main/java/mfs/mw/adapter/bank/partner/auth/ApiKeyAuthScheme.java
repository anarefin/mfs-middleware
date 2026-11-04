package mfs.mw.adapter.bank.partner.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import mfs.mw.adapter.bank.partner.AuthScheme;
import mfs.mw.adapter.bank.partner.CredentialRef;
import mfs.mw.adapter.bank.partner.CredentialResolver;
import mfs.mw.adapter.bank.partner.InboundCall;
import mfs.mw.adapter.bank.partner.OutboundCall;

/**
 * {@code api-key}: a static key in one header (param {@code header}, default {@code X-Api-Key}).
 * Verification compares in constant time.
 */
public final class ApiKeyAuthScheme implements AuthScheme {

  public static final String DEFAULT_HEADER = "X-Api-Key";

  private final String header;
  private final CredentialResolver credentials;

  public ApiKeyAuthScheme(String header, CredentialResolver credentials) {
    this.header = header;
    this.credentials = credentials;
  }

  @Override
  public String name() {
    return "api-key";
  }

  @Override
  public void apply(OutboundCall call, CredentialRef ref) {
    call.header(header, credentials.secret(ref));
  }

  @Override
  public boolean verify(InboundCall call, CredentialRef ref) {
    String presented = call.header(header);
    if (presented == null) {
      return false;
    }
    return MessageDigest.isEqual(
        presented.getBytes(StandardCharsets.UTF_8),
        credentials.secret(ref).getBytes(StandardCharsets.UTF_8));
  }
}
