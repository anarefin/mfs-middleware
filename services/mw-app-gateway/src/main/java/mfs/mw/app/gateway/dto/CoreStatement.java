package mfs.mw.app.gateway.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.List;
import mfs.mw.app.gateway.util.Sensitive;
import mfs.mw.app.gateway.util.SensitiveType;

/** WAL-12 {@code Statement} as mw-core-mock returns it. Entry {@code walletId} is not served to the app. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CoreStatement(
    @Sensitive(SensitiveType.MSISDN) String walletId,
    Instant from,
    Instant to,
    int page,
    boolean hasMore,
    List<CoreEntry> entries) {

  /** One core ledger entry (contract {@code Entry}). */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record CoreEntry(
      String txnId,
      @Sensitive(SensitiveType.MSISDN) String walletId,
      String direction,
      String kind,
      Amount amount,
      Amount balanceAfter,
      Instant at) {}
}
