package mfs.mw.adapter.merchant.partner.auth;

import mfs.mw.adapter.merchant.partner.AuthScheme;
import mfs.mw.adapter.merchant.partner.CredentialRef;
import mfs.mw.adapter.merchant.partner.InboundCall;
import mfs.mw.adapter.merchant.partner.OutboundCall;

/**
 * No credentials (simulators, partners secured by network only). Inbound calls are never
 * accepted on it: a callback must prove where it comes from.
 */
public final class NoAuthScheme implements AuthScheme {

  public static final NoAuthScheme INSTANCE = new NoAuthScheme();

  private NoAuthScheme() {}

  @Override
  public String name() {
    return "none";
  }

  @Override
  public void apply(OutboundCall call, CredentialRef ref) {
    // nothing to add
  }

  @Override
  public boolean verify(InboundCall call, CredentialRef ref) {
    return false;
  }
}
