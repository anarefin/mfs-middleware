package mfs.mw.adapter.bank.partner.config;

import java.util.List;

/**
 * A partner config that cannot be used. {@code security} is true when it broke a security rule
 * (endpoint allowlist, https, credentials outside the partner's own secrets): such a refusal is
 * logged as a security event. Messages never carry secret values.
 */
public class PartnerConfigRefusedException extends IllegalArgumentException {

  private final transient List<String> problems;
  private final boolean security;

  public PartnerConfigRefusedException(String id, List<String> problems, boolean security) {
    super("partner " + id + ": " + String.join("; ", problems));
    this.problems = List.copyOf(problems);
    this.security = security;
  }

  public List<String> problems() {
    return problems;
  }

  public boolean security() {
    return security;
  }
}
