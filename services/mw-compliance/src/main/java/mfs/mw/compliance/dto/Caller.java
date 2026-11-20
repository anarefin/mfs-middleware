package mfs.mw.compliance.dto;

import mfs.mw.compliance.util.MaskingPolicy;
import mfs.mw.compliance.util.Sensitive;
import mfs.mw.compliance.util.SensitiveType;

/**
 * Who calls the eKYC API (security review of ticket 124a): the service client ({@code azp} of its realm
 * {@code services} JWT) and, for a channel, the authenticated end user the channel vouches for in {@code
 * X-Mw-Subject-Type} / {@code X-Mw-Subject-Id} (from the app token: the customer's wallet, or the assisting
 * DSO/agent). Only an unscoped client (mw-admin-api) sees every application.
 *
 * @param clientId the calling service
 * @param subject WALLET, AGENT, or null for the unscoped caller
 * @param subjectId the wallet id or agent id
 */
public record Caller(String clientId, Subject subject, @Sensitive(SensitiveType.PARTY_ID) String subjectId) {

  /** What the channel authenticated. */
  public enum Subject {
    WALLET,
    AGENT
  }

  public static Caller wallet(String clientId, String walletId) {
    return new Caller(clientId, Subject.WALLET, walletId);
  }

  public static Caller agent(String clientId, String agentId) {
    return new Caller(clientId, Subject.AGENT, agentId);
  }

  public static Caller unscoped(String clientId) {
    return new Caller(clientId, null, null);
  }

  public boolean isUnscoped() {
    return subject == null;
  }

  @Override
  public String toString() {
    return MaskingPolicy.describe(this);
  }
}
