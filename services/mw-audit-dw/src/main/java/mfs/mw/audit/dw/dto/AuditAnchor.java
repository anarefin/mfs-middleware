package mfs.mw.audit.dw.dto;

import java.util.List;

/**
 * The claims of one signed daily anchor (spec 14.3, ticket 74), the JWS payload signed by the
 * mw-crypto key {@code AUDIT_ANCHOR_SIGN}. The audit chains are monthly, so the heads are those of
 * every monthly chain written on {@code date} (UTC): the last row it received that day.
 *
 * @param date the UTC day, {@code yyyy-MM-dd}
 * @param heads one per chain written that day, by partition; empty on a day without writes
 * @param prevAnchorHash hex SHA-256 of the previous anchor's compact JWS (64 zeros for the first)
 */
public record AuditAnchor(String date, List<Head> heads, String prevAnchorHash) {

  public AuditAnchor {
    heads = heads == null ? List.of() : List.copyOf(heads);
  }

  /**
   * @param partition the monthly chain ({@code yyyy-MM})
   * @param hash hex hash of the chain's last row of the day
   * @param rowCount rows of that chain up to and including it (its {@code seq})
   */
  public record Head(String partition, String hash, long rowCount) {}
}
